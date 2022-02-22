/**
 * Copyright (c) 2016-present, RxJava Contributors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package com.tsinglink.android.library.muxer;


import static com.tsinglink.android.library.muxer.FFMediaMuxer.AVMEDIA_TYPE_VIDEO;

import android.media.MediaCodec;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableSubscriber;
import io.reactivex.Observable;
import io.reactivex.annotations.Nullable;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.exceptions.MissingBackpressureException;
import io.reactivex.functions.Consumer;
import io.reactivex.internal.fuseable.HasUpstreamPublisher;
import io.reactivex.internal.subscriptions.BasicIntQueueSubscription;
import io.reactivex.internal.subscriptions.SubscriptionHelper;
import io.reactivex.internal.util.BackpressureHelper;
import io.reactivex.plugins.RxJavaPlugins;
import timber.log.Timber;

public final class MyFlowableOnBackpressureBuffer extends Flowable<RecordData> implements HasUpstreamPublisher<RecordData> {
    final int bufferSize;
    final boolean unbounded;
    final boolean delayError;
    final Consumer onOverflow;

    public static Flowable<RecordData> wrapObservable(Observable<RecordData> source, Consumer<RecordData> onOverflow) {
        Flowable<RecordData> f = source.toFlowable(BackpressureStrategy.MISSING);
        return RxJavaPlugins.onAssembly(new MyFlowableOnBackpressureBuffer(f, Short.MAX_VALUE, false, false, onOverflow));
    }

    /**
     * The upstream source Publisher.
     */
    protected final Flowable<RecordData> source;

    private MyFlowableOnBackpressureBuffer(Flowable<RecordData> source, int bufferSize, boolean unbounded,
                                           boolean delayError, Consumer onOverflow) {
        this.source = source;
        this.bufferSize = bufferSize;
        this.unbounded = unbounded;
        this.delayError = delayError;
        this.onOverflow = onOverflow;
    }

    @Override
    protected void subscribeActual(Subscriber<? super RecordData> s) {
        source.subscribe(new BackpressureBufferSubscriber(s, bufferSize, unbounded, delayError, onOverflow));
    }

    @Override
    public Publisher<RecordData> source() {
        return source;
    }

    static final class BackpressureBufferSubscriber extends BasicIntQueueSubscription<RecordData> implements FlowableSubscriber<RecordData> {

        private static final long serialVersionUID = -2514538129242366402L;

        final Subscriber<? super RecordData> actual;
        final ArrayBlockingQueue<RecordData> queue;
        final boolean delayError;
        final int bufSize;
        final Consumer onOverflow;
        boolean overflowing = false;

        Subscription s;

        volatile boolean cancelled;

        volatile boolean done;
        Throwable error;

        final AtomicLong requested = new AtomicLong();

        boolean outputFused;

        BackpressureBufferSubscriber(Subscriber<? super RecordData> actual, int bufferSize,
                                     boolean unbounded, boolean delayError, Consumer onOverflow) {
            this.bufSize = bufferSize;
            this.actual = actual;
            this.onOverflow = onOverflow;
            this.delayError = delayError;

            this.queue = new ArrayBlockingQueue(bufferSize);
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;
                actual.onSubscribe(this);
                s.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(RecordData t) {
            if (t.type == AVMEDIA_TYPE_VIDEO && (overflowing || queue.size() >= 30)) {
                if ((t.flag & MediaCodec.BUFFER_FLAG_KEY_FRAME) != MediaCodec.BUFFER_FLAG_KEY_FRAME) {
                    if (!overflowing) {
                        Timber.w("see non-keyframe..overflowing set.");
                    }
                    overflowing = true;
                    try {
                        onOverflow.accept(t);
                    } catch (Throwable e) {
                        s.cancel();
                        Exceptions.throwIfFatal(e);
                        onError(e);
                    }
                    return;
                } else {
                    if (overflowing) {
                        Timber.w("see keyframe..overflowing reset.");
                        overflowing = false;
                    }
                }
            }
            if (!queue.offer(t)) {
                s.cancel();
                MissingBackpressureException ex = new MissingBackpressureException("Buffer is full");
                onError(ex);
                return;
            }
            if (outputFused) {
                actual.onNext(null);
            } else {
                drain();
            }
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            done = true;
            if (outputFused) {
                actual.onError(t);
            } else {
                drain();
            }
        }

        @Override
        public void onComplete() {
            done = true;
            if (outputFused) {
                actual.onComplete();
            } else {
                drain();
            }
        }

        @Override
        public void request(long n) {
            if (!outputFused) {
                if (SubscriptionHelper.validate(n)) {
                    BackpressureHelper.add(requested, n);
                    drain();
                }
            }
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
                s.cancel();

                if (getAndIncrement() == 0) {
                    queue.clear();
                }
            }
        }

        void drain() {
            if (getAndIncrement() == 0) {
                int missed = 1;
                final ArrayBlockingQueue<RecordData> q = queue;
                final Subscriber<? super RecordData> a = actual;
                for (; ; ) {

                    if (checkTerminated(done, q.isEmpty(), a)) {
                        return;
                    }

                    long r = requested.get();

                    long e = 0L;

                    while (e != r) {
                        boolean d = done;
                        RecordData v = q.poll();
                        boolean empty = v == null;

                        if (checkTerminated(d, empty, a)) {
                            return;
                        }

                        if (empty) {
                            break;
                        }

                        a.onNext(v);

                        e++;
                    }

                    if (e == r) {
                        boolean d = done;
                        boolean empty = q.isEmpty();

                        if (checkTerminated(d, empty, a)) {
                            return;
                        }
                    }

                    if (e != 0L) {
                        if (r != Long.MAX_VALUE) {
                            requested.addAndGet(-e);
                        }
                    }

                    missed = addAndGet(-missed);
                    if (missed == 0) {
                        break;
                    }
                }
            }
        }

        boolean checkTerminated(boolean d, boolean empty, Subscriber<? super RecordData> a) {
            if (cancelled) {
                queue.clear();
                return true;
            }
            if (d) {
                if (delayError) {
                    if (empty) {
                        Throwable e = error;
                        if (e != null) {
                            a.onError(e);
                        } else {
                            a.onComplete();
                        }
                        return true;
                    }
                } else {
                    Throwable e = error;
                    if (e != null) {
                        queue.clear();
                        a.onError(e);
                        return true;
                    } else if (empty) {
                        a.onComplete();
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public int requestFusion(int mode) {
            if ((mode & ASYNC) != 0) {
                outputFused = true;
                return ASYNC;
            }
            return NONE;
        }

        @Nullable
        @Override
        public RecordData poll() throws Exception {
            return queue.poll();
        }

        @Override
        public void clear() {
            queue.clear();
        }

        @Override
        public boolean isEmpty() {
            return queue.isEmpty();
        }
    }
}
