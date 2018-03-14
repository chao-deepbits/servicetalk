/**
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.concurrent.api;

import io.servicetalk.concurrent.Cancellable;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * An asynchronous computation that produces 0, 1 or more elements and may or may not terminate successfully or with an error.
 *
 * @param <T> Type of items emitted.
 *
 * @see org.reactivestreams.Publisher
 */
public abstract class Publisher<T> implements org.reactivestreams.Publisher<T> {
    private static final AtomicReference<BiConsumer<? super Subscriber, Consumer<? super Subscriber>>> SUBSCRIBE_PLUGIN_REF = new AtomicReference<>();

    /**
     * Add a plugin that will be invoked on each {@link #subscribe(Subscriber)} call. This can be used for visibility or to
     * extend functionality to all {@link Subscriber}s which pass through {@link #subscribe(Subscriber)}.
     * @param subscribePlugin the plugin that will be invoked on each {@link #subscribe(Subscriber)} call.
     */
    @SuppressWarnings("rawtypes")
    public static void addSubscribePlugin(BiConsumer<? super Subscriber, Consumer<? super Subscriber>> subscribePlugin) {
        requireNonNull(subscribePlugin);
        SUBSCRIBE_PLUGIN_REF.updateAndGet(currentPlugin -> currentPlugin == null ? subscribePlugin :
                (subscriber, handleSubscribe) -> subscribePlugin.accept(subscriber, subscriber2 -> subscribePlugin.accept(subscriber2, handleSubscribe))
        );
    }

    /**
     * Handles a subscriber to this {@code Publisher}.
     *
     * @param subscriber the subscriber.
     */
    protected abstract void handleSubscribe(Subscriber<? super T> subscriber);

    @Override
    public final void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber);
        BiConsumer<? super Subscriber, Consumer<? super Subscriber>> plugin = SUBSCRIBE_PLUGIN_REF.get();
        if (plugin != null) {
            plugin.accept(subscriber, this::handleSubscribe);
        } else {
            handleSubscribe(subscriber);
        }
    }

    /**
     * Converts this {@link Publisher} to a {@link Single}.
     *
     * @return A {@link Single} that will contain the first item emitted from the this {@link Publisher}.
     * If the source {@link Publisher} does not emit any item, then the returned {@link Single} will terminate with {@link NoSuchElementException}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX first operator.</a>
     */
    public final Single<T> first() {
        return new PubToSingle<>(this);
    }

    /**
     * Ignores all elements emitted by this {@link Publisher} and forwards the termination signal to the returned {@link Completable}.
     *
     * @return A {@link Completable} that mirrors the terminal signal from this {@code Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/ignoreelements.html">ReactiveX ignoreElements operator.</a>
     */
    public final Completable ignoreElements() {
        return new PubToCompletable<>(this);
    }

    /**
     * Ignores any error returned by this {@link Publisher} and resume to a new {@link Publisher}.
     *
     * @param nextFactory Returns the next {@link Publisher}, when this {@link Publisher} emits an error.
     * @return A {@link Publisher} that ignores error from this {@code Publisher} and resume with the {@link Publisher} produced by {@code nextFactory}.
     * @see <a href="http://reactivex.io/documentation/operators/catch.html">ReactiveX catch operator.</a>
     */
    public final Publisher<T> onErrorResume(Function<Throwable, Publisher<T>> nextFactory) {
        return new ResumePublisher<>(this, nextFactory);
    }

    /**
     * Transforms elements emitted by this {@link Publisher} into a different type.
     *
     * @param mapper Function to transform each item emitted by this {@link Publisher}.
     * @param <R> Type of the items emitted by the returned {@link Publisher}.
     * @return A {@link Publisher} that transforms elements emitted by this {@link Publisher} into a different type.
     *
     * @see <a href="http://reactivex.io/documentation/operators/map.html">ReactiveX map operator.</a>
     */
    public final <R> Publisher<R> map(Function<T, R> mapper) {
        return new MapPublisher<>(this, mapper);
    }

    /**
     * Filters items emitted by this {@link Publisher}.
     *
     * @param predicate for the filter.
     * @return A {@link Publisher} that only emits the items that pass the {@code predicate}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/filter.html">ReactiveX filter operator.</a>
     */
    public final Publisher<T> filter(Predicate<T> predicate) {
        return new FilterPublisher<>(this, predicate);
    }

    /**
     * Takes at most {@code numElements} elements from {@code this} {@link Publisher}.<p>
     *  If no terminal event is received before receiving {@code numElements} elements, {@link Subscription} for the {@link Subscriber} is cancelled.
     *
     * @param numElements Number of elements to take.
     * @return A {@link Publisher} that emits at most {@code numElements} elements from {@code this} {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/take.html">ReactiveX take operator.</a>
     */
    public final Publisher<T> take(long numElements) {
        return new TakeNPublisher<>(this, numElements);
    }

    /**
     * Takes elements while {@link Predicate} is {@code true} and then cancel {@link Subscription} of this
     * {@link Publisher} once it returns {@code false}.
     *
     * @param predicate {@link Predicate} that is checked before emitting any item to a {@link Subscriber}.
     *                  If this predicate returns {@code true}, that item is emitted, else {@link Subscription} is cancelled.
     * @return A {@link Publisher} that only emits the items as long as the {@link Predicate#test(Object)} method
     *         returns {@code true}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/takewhile.html">ReactiveX takeWhile operator.</a>
     */
    public final Publisher<T> takeWhile(Predicate<T> predicate) {
        return new TakeWhilePublisher<>(this, predicate);
    }

    /**
     * Takes elements until {@link Completable} is terminated successfully or with failure.
     *
     * @param until {@link Completable}, termination of which, terminates the returned {@link Publisher}.
     * @return A {@link Publisher} that only emits the items till {@code until} {@link Completable} is completed.
     *
     * @see <a href="http://reactivex.io/documentation/operators/takeuntil.html">ReactiveX takeUntil operator.</a>
     */
    public final Publisher<T> takeUntil(Completable until) {
        return new TakeUntilPublisher<>(this, until);
    }

    /**
     * Turns every item emitted by this {@link Publisher} into a {@link Single} and emits the items emitted by each of those {@link Single}s.
     *
     * @param mapper Function to convert each item emitted by this {@link Publisher} into a {@link Single}.
     * @param maxConcurrency Maximum active {@link Single}s at any time.
     *                       Even if the number of items requested by a {@link Subscriber} is more than this number, this will never request more than this number at any point.
     * @param <R> Type of items emitted by the returned {@link Publisher}.
     * @return A new {@link Publisher} that emits all items emitted by each single produced by {@code mapper}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX flatmap operator.</a>
     */
    public final <R> Publisher<R> flatmapSingle(Function<T, Single<R>> mapper, int maxConcurrency) {
        return new PublisherFlatmapSingle<>(this, mapper, maxConcurrency, false);
    }

    /**
     * Turns every item emitted by this {@link Publisher} into a {@link Single} and emits the items emitted by each of those {@link Single}s.
     * This is the same as {@link #flatmapSingle(Function, int)} just that if any {@link Single} returned by {@code mapper}, terminates with an error,
     * the returned {@link Publisher} will not immediately terminate. Instead, it will wait for this {@link Publisher} and all {@link Single}s to terminate and then
     * terminate the returned {@link Publisher} with all errors emitted by the {@link Single}s produced by the {@code mapper}.
     *
     * @param mapper Function to convert each item emitted by this {@link Publisher} into a {@link Single}.
     * @param maxConcurrency Maximum active {@link Single}s at any time.
     *                       Even if the number of items requested by a {@link Subscriber} is more than this number, this will never request more than this number at any point.
     * @param <R> Type of items emitted by the returned {@link Publisher}.
     * @return A new {@link Publisher} that emits all items emitted by each single produced by {@code mapper}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX merge operator.</a>
     */
    public final <R> Publisher<R> flatmapSingleDelayError(Function<T, Single<R>> mapper, int maxConcurrency) {
        return new PublisherFlatmapSingle<>(this, mapper, maxConcurrency, true);
    }

    /**
     * Create a {@link Publisher} that flattens each element returned by the {@link Iterable#iterator()} from {@code mapper}.
     * @param mapper A {@link Function} that returns an {@link Iterable} for each element.
     * @param <U> The elements returned by the {@link Iterable}.
     * @return a {@link Publisher} that flattens each element returned by the {@link Iterable#iterator()} from {@code mapper}.
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX FlatMap operator.</a>
     */
    public final <U> Publisher<U> flatMapIterable(Function<? super T, ? extends Iterable<? extends U>> mapper) {
        return new PublisherFlatMapIterable<>(this, mapper);
    }

    /**
     * Reduces the stream into a single item.
     *
     * @param resultFactory Factory for the result which collects all items emitted by this {@link Publisher}.
     *                      This will be called every time the returned {@link Single} is subscribed.
     * @param reducer Invoked for every item emitted by the source {@link Publisher} and returns the same or altered {@code result} object.
     * @param <R> Type of the reduced item.
     * @return A {@link Single} that completes with the single {@code result} or any error emitted by the source {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/reduce.html">ReactiveX reduce operator.</a>
     */
    public final <R> Single<R> reduce(Supplier<R> resultFactory, BiFunction<R, ? super T, R> reducer) {
        return new ReduceSingle<>(this, resultFactory, reducer);
    }

    /**
     * Splits items from this {@link Publisher} into dynamically generated {@link Group}s.
     * Item to group association is done by {@code keySelector} {@link Function}. If the selector selects a key which is previously seen and its associated
     * {@link Subscriber} has not yet cancelled its {@link Subscription}, this item is sent to that {@link Subscriber}.
     * Otherwise a new {@link Group} is created and emitted from the returned {@link Publisher}.
     *
     * <h2>Flow control</h2>
     * Multiple {@link Subscriber}s (for multiple {@link Group}s) request items individually from this {@link Publisher}.
     * Since, there is no way for a {@link Subscriber} to only request elements for its group, elements requested by one group
     * may end up producing items for a different group, which has not yet requested enough.
     * This will cause items to be queued per group which can not be emitted due to lack of demand.
     * This queue size can be controlled with the {@code maxQueuePerGroup} argument.
     *
     * <h2>Cancellation</h2>
     *
     * If the {@link Subscriber} of the returned {@link Publisher} cancels its {@link Subscription},
     * then all active {@link Group}s will be terminated with an error and the {@link Subscription} to this {@link Publisher} will be cancelled.<p>
     *     {@link Subscriber}s of individual {@link Group}s can cancel their {@link Subscription}s at any point.
     *     If any new item is emitted for the cancelled {@link Group}, a new {@link Group} will be emitted from the returned {@link Publisher}.
     *     Any queued items for a cancelled {@link Subscriber} for a {@link Group} will be discarded and hence will not be emitted if the same {@link Group} is emitted again.
     *
     * @param keySelector {@link Function} to assign an item emitted by this {@link Publisher} to a {@link Group}.
     * @param groupMaxQueueSize Maximum number of new groups that will be queued due to the {@link Subscriber} of the {@link Publisher} returned from this
     *                      method not requesting enough via {@link Subscription#request(long)}.
     * @param <Key> Type of {@link Group} keys.
     * @return A {@link Publisher} that emits {@link Group}s for new {@code key}s as emitted by {@code keySelector} {@link Function}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/groupby.html">ReactiveX groupBy operator.</a>
     */
    public final <Key> Publisher<Group<Key, T>> groupBy(Function<T, Key> keySelector, int groupMaxQueueSize) {
        return new PublisherGroupBy<>(this, keySelector, groupMaxQueueSize);
    }

    /**
     * Splits items from this {@link Publisher} into dynamically generated {@link Group}s.
     * Item to group association is done by {@code keySelector} {@link Function}. If the selector selects a key which is previously seen and its associated
     * {@link Subscriber} has not yet cancelled its {@link Subscription}, this item is sent to that {@link Subscriber}.
     * Otherwise a new {@link Group} is created and emitted from the returned {@link Publisher}.
     *
     * <h2>Flow control</h2>
     * Multiple {@link Subscriber}s (for multiple {@link Group}s) request items individually from this {@link Publisher}.
     * Since, there is no way for a {@link Subscriber} to only request elements for its group, elements requested by one group
     * may end up producing items for a different group, which has not yet requested enough.
     * This will cause items to be queued per group which can not be emitted due to lack of demand.
     * This queue size can be controlled with the {@code maxQueuePerGroup} argument.
     *
     * <h2>Cancellation</h2>
     *
     * If the {@link Subscriber} of the returned {@link Publisher} cancels its {@link Subscription},
     * then all active {@link Group}s will be terminated with an error and the {@link Subscription} to this {@link Publisher} will be cancelled.<p>
     *     {@link Subscriber}s of individual {@link Group}s can cancel their {@link Subscription}s at any point.
     *     If any new item is emitted for the cancelled {@link Group}, a new {@link Group} will be emitted from the returned {@link Publisher}.
     *     Any queued items for a cancelled {@link Subscriber} for a {@link Group} will be discarded and hence will not be emitted if the same {@link Group} is emitted again.
     *
     * @param keySelector {@link Function} to assign an item emitted by this {@link Publisher} to a {@link Group}.
     * @param groupMaxQueueSize Maximum number of new groups that will be queued due to the {@link Subscriber} of the {@link Publisher} returned from this
     *                      method not requesting enough via {@link Subscription#request(long)}.
     * @param expectedGroupCountHint Expected number of groups that would be emitted by {@code this} {@link Publisher}.
     *                               This is just a hint for internal data structures and does not have to be precise.
     * @param <Key> Type of {@link Group} keys.
     * @return A {@link Publisher} that emits {@link Group}s for new {@code key}s as emitted by {@code keySelector} {@link Function}.
     * @see <a href="http://reactivex.io/documentation/operators/groupby.html">ReactiveX groupBy operator.</a>
     */
    public final <Key> Publisher<Group<Key, T>> groupBy(Function<T, Key> keySelector, int groupMaxQueueSize, int expectedGroupCountHint) {
        return new PublisherGroupBy<>(this, keySelector, groupMaxQueueSize, expectedGroupCountHint);
    }

    /**
     * The semantics are identical to {@link #groupBy(Function, int)} except that the {@code keySelector} can map each data to multiple keys.
     *
     * @param keySelector {@link Function} to assign an item emitted by this {@link Publisher} to multiple {@link Group}s.
     * @param groupMaxQueueSize Maximum number of new groups that will be queued due to the {@link Subscriber} of the {@link Publisher} returned from this
     *                      method not requesting enough via {@link Subscription#request(long)}.
     * @param <Key> Type of {@link Group} keys.
     * @return A {@link Publisher} that emits {@link Group}s for new {@code key}s as emitted by {@code keySelector} {@link Function}.
     * @see #groupBy(Function, int)
     */
    public final <Key> Publisher<Group<Key, T>> groupByMulti(Function<T, Iterator<Key>> keySelector, int groupMaxQueueSize) {
        return new PublisherGroupByMulti<>(this, keySelector, groupMaxQueueSize);
    }

    /**
     * The semantics are identical to {@link #groupBy(Function, int)} except that the {@code keySelector} can map each data to multiple keys.
     *
     * @param keySelector {@link Function} to assign an item emitted by this {@link Publisher} to multiple {@link Group}s.
     * @param groupMaxQueueSize Maximum number of new groups that will be queued due to the {@link Subscriber} of the {@link Publisher} returned from this
     *                      method not requesting enough via {@link Subscription#request(long)}.
     * @param expectedGroupCountHint Expected number of groups that would be emitted by {@code this} {@link Publisher}.
     *                               This is just a hint for internal data structures and does not have to be precise.
     * @param <Key> Type of {@link Group} keys.
     * @return A {@link Publisher} that emits {@link Group}s for new {@code key}s as emitted by {@code keySelector} {@link Function}.
     * @see #groupBy(Function, int)
     */
    public final <Key> Publisher<Group<Key, T>> groupByMulti(Function<T, Iterator<Key>> keySelector, int groupMaxQueueSize, int expectedGroupCountHint) {
        return new PublisherGroupByMulti<>(this, keySelector, groupMaxQueueSize, expectedGroupCountHint);
    }

    /**
     * Create a {@link Publisher} that allows exactly {@code expectedSubscribers} calls to {@link #subscribe(Subscriber)}.
     * The events from this {@link Publisher} object will be delivered to each {@link Subscriber}.
     * <p>
     * Depending on {@link Subscription#request(long)} demand it is possible that data maybe queued before being delivered
     * to each {@link Subscriber}! For example if there are 2 {@link Subscriber}s and the first calls
     * {@link Subscription#request(long) request(10)}, and the second only calls {@link Subscription#request(long) request(10)},
     * then 9 elements will be queued to deliver to second when more {@link Subscription#request(long)} demand is made.
     * @param expectedSubscribers The number of expected {@link #subscribe(Subscriber)} calls required on the returned
     *          {@link Publisher} before calling {@link #subscribe(Subscriber)} on this {@link Publisher}.
     * @return a {@link Publisher} that allows exactly {@code expectedSubscribers} calls to {@link #subscribe(Subscriber)}.
     */
    public final Publisher<T> multicast(int expectedSubscribers) {
        return new MulticastPublisher<>(this, expectedSubscribers);
    }

    /**
     * Create a {@link Publisher} that allows exactly {@code expectedSubscribers} calls to {@link #subscribe(Subscriber)}.
     * The events from this {@link Publisher} object will be delivered to each {@link Subscriber}.
     * <p>
     * Depending on {@link Subscription#request(long)} demand it is possible that data maybe queued before being delivered
     * to each {@link Subscriber}! For example if there are 2 {@link Subscriber}s and the first calls
     * {@link Subscription#request(long) request(10)}, and the second only calls {@link Subscription#request(long) request(10)},
     * then 9 elements will be queued to deliver to second when more {@link Subscription#request(long)} demand is made.
     * @param expectedSubscribers The number of expected {@link #subscribe(Subscriber)} calls required on the returned
     *          {@link Publisher} before calling {@link #subscribe(Subscriber)} on this {@link Publisher}.
     * @param maxQueueSize The maximum number of {@link Subscriber#onNext(Object)} events that will be queued if there is no
     *                     demand for data before the {@link Subscriber} will be discarded.
     * @return a {@link Publisher} that allows exactly {@code expectedSubscribers} calls to {@link #subscribe(Subscriber)}.
     */
    public final Publisher<T> multicast(int expectedSubscribers, int maxQueueSize) {
        return new MulticastPublisher<>(this, expectedSubscribers, maxQueueSize);
    }

    /**
     * Invokes the {@code onSubscribe} {@link Consumer} argument <strong>before</strong> {@link Subscriber#onSubscribe(Subscription)} is called for {@link Subscriber}s of the returned {@link Publisher}.
     *
     * @param onSubscribe Invoked <strong>before</strong> {@link Subscriber#onSubscribe(Subscription)} is called for {@link Subscriber}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doBeforeSubscribe(Consumer<Subscription> onSubscribe) {
        requireNonNull(onSubscribe);
        Subscriber<T> subscriber = new Subscriber<T>() {
            @Override
            public void onSubscribe(Subscription s) {
                onSubscribe.accept(s);
            }

            @Override
            public void onNext(T t) {
                // NOOP
            }

            @Override
            public void onError(Throwable t) {
                // NOOP
            }

            @Override
            public void onComplete() {
                // NOOP
            }
        };
        return doBeforeSubscriber(() -> subscriber);
    }

    /**
     * Invokes the {@code onNext} {@link Consumer} argument <strong>before</strong> {@link Subscriber#onNext(Object)} is called for {@link Subscriber}s of the returned {@link Publisher}.
     *
     * @param onNext Invoked <strong>before</strong> {@link Subscriber#onNext(Object)} is called for {@link Subscriber}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doBeforeNext(Consumer<T> onNext) {
        requireNonNull(onNext);
        Subscriber<T> subscriber = new Subscriber<T>() {
            @Override
            public void onSubscribe(Subscription s) {
                // NOOP
            }

            @Override
            public void onNext(T t) {
                onNext.accept(t);
            }

            @Override
            public void onError(Throwable t) {
                // NOOP
            }

            @Override
            public void onComplete() {
                // NOOP
            }
        };
        return doBeforeSubscriber(() -> subscriber);
    }

    /**
     * Invokes the {@code onError} {@link Consumer} argument <strong>before</strong> {@link Subscriber#onError(Throwable)} is called for {@link Subscriber}s of the returned {@link Publisher}.
     *
     * @param onError Invoked <strong>before</strong> {@link Subscriber#onError(Throwable)} is called for {@link Subscriber}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doBeforeError(Consumer<Throwable> onError) {
        requireNonNull(onError);
        Subscriber<T> subscriber = new Subscriber<T>() {
            @Override
            public void onSubscribe(Subscription s) {
                // NOOP
            }

            @Override
            public void onNext(T t) {
                // NOOP
            }

            @Override
            public void onError(Throwable t) {
                onError.accept(t);
            }

            @Override
            public void onComplete() {
                // NOOP
            }
        };
        return doBeforeSubscriber(() -> subscriber);
    }

    /**
     * Invokes the {@code onComplete} {@link Runnable} argument <strong>before</strong> {@link Subscriber#onComplete()} is called for {@link Subscriber}s of the returned {@link Publisher}.
     *
     * @param onComplete Invoked <strong>before</strong> {@link Subscriber#onComplete()} is called for {@link Subscriber}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doBeforeComplete(Runnable onComplete) {
        requireNonNull(onComplete);
        Subscriber<T> subscriber = new Subscriber<T>() {
            @Override
            public void onSubscribe(Subscription s) {
                // NOOP
            }

            @Override
            public void onNext(T t) {
                // NOOP
            }

            @Override
            public void onError(Throwable t) {
                // NOOP
            }

            @Override
            public void onComplete() {
                onComplete.run();
            }
        };
        return doBeforeSubscriber(() -> subscriber);
    }

    /**
     * Invokes the {@code onRequest} {@link LongConsumer} argument <strong>before</strong> {@link Subscription#request(long)} is called for {@link Subscription}s of the returned {@link Publisher}.
     *
     * @param onRequest Invoked <strong>before</strong> {@link Subscription#request(long)} is called for {@link Subscription}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doBeforeRequest(LongConsumer onRequest) {
        requireNonNull(onRequest);
        Subscription subscription = new Subscription() {
            @Override
            public void request(long n) {
                onRequest.accept(n);
            }

            @Override
            public void cancel() {
                // NOOP
            }
        };
        return doBeforeSubscription(() -> subscription);
    }

    /**
     * Invokes the {@code onCancel} {@link Runnable} argument <strong>before</strong> {@link Subscription#cancel()} is called for {@link Subscription}s of the returned {@link Publisher}.
     *
     * @param onCancel Invoked <strong>before</strong> {@link Subscription#cancel()} is called for {@link Subscription}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doBeforeCancel(Runnable onCancel) {
        requireNonNull(onCancel);
        Subscription subscription = new Subscription() {
            @Override
            public void request(long n) {
                //  NOOP
            }

            @Override
            public void cancel() {
                onCancel.run();
            }
        };
        return doBeforeSubscription(() -> subscription);
    }

    /**
     * Invokes the {@code doFinally} {@link Runnable} argument <strong>before</strong> any of the following terminal methods are called:
     * <ul>
     *     <li>{@link Subscriber#onComplete()}</li>
     *     <li>{@link Subscriber#onError(Throwable)}</li>
     *     <li>{@link Subscription#cancel()}</li>
     * </ul>
     * for {@link Subscription}s/{@link Subscriber}s of the returned {@link Publisher}.
     *
     * @param doFinally Invoked <strong>before</strong> any of the following terminal methods are called:
     * <ul>
     *     <li>{@link Subscriber#onComplete()}</li>
     *     <li>{@link Subscriber#onError(Throwable)}</li>
     *     <li>{@link Subscription#cancel()}</li>
     * </ul>
     * for {@link Subscription}s/{@link Subscriber}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doBeforeFinally(Runnable doFinally) {
        return new DoBeforeFinallyPublisher<>(this, doFinally);
    }

    /**
     * Creates a new {@link Subscriber} (via the {@code subscriberSupplier} argument) on each call to {@link #subscribe(Subscriber)} and invokes
     * all the {@link Subscriber} methods <strong>before</strong> the {@link Subscriber}s of the returned {@link Publisher}.
     *
     * @param subscriberSupplier Creates a new {@link Subscriber} on each call to {@link #subscribe(Subscriber)} and invokes
     * all the {@link Subscriber} methods <strong>before</strong> the {@link Subscriber}s of the returned {@link Publisher}. {@link Subscriber} methods <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doBeforeSubscriber(Supplier<Subscriber<? super T>> subscriberSupplier) {
        return new DoBeforeSubscriberPublisher<>(this, subscriberSupplier);
    }

    /**
     * Creates a new {@link Subscription} (via the {@code subscriberSupplier} argument) on each call to {@link #subscribe(Subscriber)} and invokes
     * all the {@link Subscription} methods <strong>before</strong> the {@link Subscription}s of the returned {@link Publisher}.
     *
     * @param subscriptionSupplier Creates a new {@link Subscription} on each call to {@link #subscribe(Subscriber)} and invokes
     * all the {@link Subscription} methods <strong>before</strong> the {@link Subscription}s of the returned {@link Publisher}. {@link Subscription} methods <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doBeforeSubscription(Supplier<Subscription> subscriptionSupplier) {
        return new DoSubscriptionPublisher<>(this, subscriptionSupplier, true);
    }

    /**
     * Invokes the {@code onSubscribe} {@link Consumer} argument <strong>after</strong> {@link Subscriber#onSubscribe(Subscription)} is called for {@link Subscriber}s of the returned {@link Publisher}.
     *
     * @param onSubscribe Invoked <strong>after</strong> {@link Subscriber#onSubscribe(Subscription)} is called for {@link Subscriber}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doAfterSubscribe(Consumer<Subscription> onSubscribe) {
        requireNonNull(onSubscribe);
        Subscriber<T> subscriber = new Subscriber<T>() {
            @Override
            public void onSubscribe(Subscription s) {
                onSubscribe.accept(s);
            }

            @Override
            public void onNext(T t) {
                // NOOP
            }

            @Override
            public void onError(Throwable t) {
                // NOOP
            }

            @Override
            public void onComplete() {
                // NOOP
            }
        };
        return doAfterSubscriber(() -> subscriber);
    }

    /**
     * Invokes the {@code onNext} {@link Consumer} argument <strong>after</strong> {@link Subscriber#onNext(Object)} is called for {@link Subscriber}s of the returned {@link Publisher}.
     *
     * @param onNext Invoked <strong>after</strong> {@link Subscriber#onNext(Object)} is called for {@link Subscriber}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doAfterNext(Consumer<T> onNext) {
        requireNonNull(onNext);
        Subscriber<T> subscriber = new Subscriber<T>() {
            @Override
            public void onSubscribe(Subscription s) {
                // NOOP
            }

            @Override
            public void onNext(T t) {
                onNext.accept(t);
            }

            @Override
            public void onError(Throwable t) {
                // NOOP
            }

            @Override
            public void onComplete() {
                // NOOP
            }
        };
        return doAfterSubscriber(() -> subscriber);
    }

    /**
     * Invokes the {@code onError} {@link Consumer} argument <strong>after</strong> {@link Subscriber#onError(Throwable)} is called for {@link Subscriber}s of the returned {@link Publisher}.
     *
     * @param onError Invoked <strong>after</strong> {@link Subscriber#onError(Throwable)} is called for {@link Subscriber}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doAfterError(Consumer<Throwable> onError) {
        requireNonNull(onError);
        Subscriber<T> subscriber = new Subscriber<T>() {
            @Override
            public void onSubscribe(Subscription s) {
                // NOOP
            }

            @Override
            public void onNext(T t) {
                // NOOP
            }

            @Override
            public void onError(Throwable t) {
                onError.accept(t);
            }

            @Override
            public void onComplete() {
                // NOOP
            }
        };
        return doAfterSubscriber(() -> subscriber);
    }

    /**
     * Invokes the {@code onComplete} {@link Runnable} argument <strong>after</strong> {@link Subscriber#onComplete()} is called for {@link Subscriber}s of the returned {@link Publisher}.
     *
     * @param onComplete Invoked <strong>after</strong> {@link Subscriber#onComplete()} is called for {@link Subscriber}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doAfterComplete(Runnable onComplete) {
        requireNonNull(onComplete);
        Subscriber<T> subscriber = new Subscriber<T>() {
            @Override
            public void onSubscribe(Subscription s) {
                // NOOP
            }

            @Override
            public void onNext(T t) {
                // NOOP
            }

            @Override
            public void onError(Throwable t) {
                // NOOP
            }

            @Override
            public void onComplete() {
                onComplete.run();
            }
        };
        return doAfterSubscriber(() -> subscriber);
    }

    /**
     * Invokes the {@code onRequest} {@link LongConsumer} argument <strong>after</strong> {@link Subscription#request(long)} is called for {@link Subscription}s of the returned {@link Publisher}.
     *
     * @param onRequest Invoked <strong>after</strong> {@link Subscription#request(long)} is called for {@link Subscription}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doAfterRequest(LongConsumer onRequest) {
        requireNonNull(onRequest);
        Subscription subscription = new Subscription() {
            @Override
            public void request(long n) {
                onRequest.accept(n);
            }

            @Override
            public void cancel() {
                // NOOP
            }
        };
        return doAfterSubscription(() -> subscription);
    }

    /**
     * Invokes the {@code onCancel} {@link Runnable} argument <strong>after</strong> {@link Subscription#cancel()} is called for {@link Subscription}s of the returned {@link Publisher}.
     *
     * @param onCancel Invoked <strong>after</strong> {@link Subscription#cancel()} is called for {@link Subscription}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doAfterCancel(Runnable onCancel) {
        requireNonNull(onCancel);
        Subscription subscription = new Subscription() {
            @Override
            public void request(long n) {
                //  NOOP
            }

            @Override
            public void cancel() {
                onCancel.run();
            }
        };
        return doAfterSubscription(() -> subscription);
    }

    /**
     * Invokes the {@code doFinally} {@link Runnable} argument <strong>after</strong> any of the following terminal methods are called:
     * <ul>
     *     <li>{@link Subscriber#onComplete()}</li>
     *     <li>{@link Subscriber#onError(Throwable)}</li>
     *     <li>{@link Subscription#cancel()}</li>
     * </ul>
     * for {@link Subscription}s/{@link Subscriber}s of the returned {@link Publisher}.
     *
     * @param doFinally Invoked <strong>after</strong> any of the following terminal methods are called:
     * <ul>
     *     <li>{@link Subscriber#onComplete()}</li>
     *     <li>{@link Subscriber#onError(Throwable)}</li>
     *     <li>{@link Subscription#cancel()}</li>
     * </ul>
     * for {@link Subscription}s/{@link Subscriber}s of the returned {@link Publisher}. <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doAfterFinally(Runnable doFinally) {
        return new DoAfterFinallyPublisher<>(this, doFinally);
    }

    /**
     * Creates a new {@link Subscriber} (via the {@code subscriberSupplier} argument) on each call to {@link #subscribe(Subscriber)} and invokes
     * all the {@link Subscriber} methods <strong>after</strong> the {@link Subscriber}s of the returned {@link Publisher}.
     *
     * @param subscriberSupplier Creates a new {@link Subscriber} on each call to {@link #subscribe(Subscriber)} and invokes
     * all the {@link Subscriber} methods <strong>after</strong> the {@link Subscriber}s of the returned {@link Publisher}. {@link Subscriber} methods <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doAfterSubscriber(Supplier<Subscriber<? super T>> subscriberSupplier) {
        return new DoAfterSubscriberPublisher<>(this, subscriberSupplier);
    }

    /**
     * Creates a new {@link Subscription} (via the {@code subscriberSupplier} argument) on each call to {@link #subscribe(Subscriber)} and invokes
     * all the {@link Subscription} methods <strong>after</strong> the {@link Subscription}s of the returned {@link Publisher}.
     *
     * @param subscriptionSupplier Creates a new {@link Subscription} on each call to {@link #subscribe(Subscriber)} and invokes
     * all the {@link Subscription} methods <strong>after</strong> the {@link Subscription}s of the returned {@link Publisher}. {@link Subscription} methods <strong>MUST NOT</strong> throw.
     * @return The new {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX do operator.</a>
     */
    public final Publisher<T> doAfterSubscription(Supplier<Subscription> subscriptionSupplier) {
        return new DoSubscriptionPublisher<>(this, subscriptionSupplier, false);
    }

    /**
     * Emits items emitted by {@code next} {@link Publisher} after {@code this} {@link Publisher} terminates successfully.
     *
     * @param next {@link Publisher}'s items that are emitted after {@code this} {@link Publisher} terminates successfully.
     * @return A {@link Publisher} that emits all items from this {@link Publisher} and {@code next} {@link Publisher}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/concat.html">ReactiveX concat operator.</a>
     */
    public final Publisher<T> concatWith(Publisher<T> next) {
        return new ConcatPublisher<>(this, next);
    }

    /**
     * Listens and emits the result of {@code next} {@link Single} after {@code this} {@link Publisher} terminates successfully.
     *
     * @param next {@link Single}'s result that is emitted after {@code this} {@link Publisher} terminates successfully.
     * @return A {@link Publisher} that emits all items from this {@link Publisher} and the result of {@code next} {@link Single}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/concat.html">ReactiveX concat operator.</a>
     */
    public final Publisher<T> concatWith(Single<T> next) {
        return new ConcatPublisher<>(this, next.toPublisher());
    }

    /**
     * Listens for completion of {@code next} {@link Completable} after {@code this} {@link Publisher} terminates successfully.
     * Any error from {@code this} {@link Publisher} and {@code next} {@link Completable} is forwarded to the returned {@link Publisher}.
     *
     * @param next {@link Completable} to wait for completion after {@code this} {@link Publisher} terminates successfully.
     * @return A {@link Publisher} that emits all items from this {@link Publisher} and then awaits successful completion of {@code next} {@link Completable}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/concat.html">ReactiveX concat operator.</a>
     */
    public final Publisher<T> concatWith(Completable next) {
        // We can not use next.toPublisher() here as that returns Publisher<Void> which can not be concatenated with Publisher<T>
        return new ConcatPublisher<>(this, next.andThen(empty()));
    }

    /**
     * Re-subscribes to this {@link Publisher} if an error is emitted and the passed {@link BiIntPredicate} returns {@code true}.
     *
     * @param shouldRetry {@link BiIntPredicate} that given the retry count and the most recent {@link Throwable} emitted from this
     * {@link Publisher} determines if the operation should be retried.
     * @return A {@link Publisher} that emits all items from this {@link Publisher} and re-subscribes if an error is emitted if the passed {@link BiIntPredicate} returned {@code true}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/retry.html">ReactiveX retry operator.</a>
     */
    public final Publisher<T> retry(BiIntPredicate<Throwable> shouldRetry) {
        return new RedoPublisher<>(this, (retryCount, terminalNotification) -> terminalNotification.getCause() != null && shouldRetry.test(retryCount, terminalNotification.getCause()));
    }

    /**
     * Re-subscribes to this {@link Publisher} if an error is emitted and the {@link Completable} returned by the supplied {@link BiIntFunction} completes successfully.
     * If the returned {@link Completable} emits an error, the returned {@link Publisher} terminates with that error.
     *
     * @param retryWhen {@link BiIntFunction} that given the retry count and the most recent {@link Throwable} emitted from this
     * {@link Publisher} returns a {@link Completable}. If this {@link Completable} emits an error, that error is emitted from the returned {@link Publisher},
     * otherwise, original {@link Publisher} is re-subscribed when this {@link Completable} completes.
     *
     * @return A {@link Publisher} that emits all items from this {@link Publisher} and re-subscribes if an error is emitted
     * and {@link Completable} returned by {@link BiIntFunction} completes successfully.
     *
     * @see <a href="http://reactivex.io/documentation/operators/retry.html">ReactiveX retry operator.</a>
     */
    public final Publisher<T> retryWhen(BiIntFunction<Throwable, Completable> retryWhen) {
        return new RedoWhenPublisher<>(this, (retryCount, notification) -> {
            if (notification.getCause() == null) {
                return Completable.completed();
            }
            return retryWhen.apply(retryCount, notification.getCause());
        }, true);
    }

    /**
     * Re-subscribes to this {@link Publisher} when it completes and the passed {@link IntPredicate} returns {@code true}.
     *
     * @param shouldRepeat {@link IntPredicate} that given the repeat count determines if the operation should be repeated.
     * @return A {@link Publisher} that emits all items from this {@link Publisher} and re-subscribes when it completes if the passed {@link IntPredicate} returns {@code true}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/repeat.html">ReactiveX repeat operator.</a>
     */
    public final Publisher<T> repeat(IntPredicate shouldRepeat) {
        return new RedoPublisher<>(this, (repeatCount, terminalNotification) -> terminalNotification.getCause() == null && shouldRepeat.test(repeatCount));
    }

    /**
     * Re-subscribes to this {@link Publisher} when it completes and the {@link Completable} returned by the supplied {@link IntFunction} completes successfully.
     * If the returned {@link Completable} emits an error, the returned {@link Publisher} is completed.
     *
     * @param repeatWhen {@link IntFunction} that given the repeat count returns a {@link Completable}.
     * If this {@link Completable} emits an error repeat is terminated, otherwise, original {@link Publisher} is re-subscribed
     * when this {@link Completable} completes.
     *
     * @return A {@link Publisher} that emits all items from this {@link Publisher} and re-subscribes if an error is emitted
     * and {@link Completable} returned by {@link IntFunction} completes successfully.
     *
     * @see <a href="http://reactivex.io/documentation/operators/retry.html">ReactiveX retry operator.</a>
     */
    public final Publisher<T> repeatWhen(IntFunction<Completable> repeatWhen) {
        return new RedoWhenPublisher<>(this, (retryCount, notification) -> {
            if (notification.getCause() != null) {
                return Completable.completed();
            }
            return repeatWhen.apply(retryCount);
        }, false);
    }

    /**
     * Subscribes to this {@link Publisher} and invokes {@code forEach} {@link Consumer} for each item emitted by this {@link Publisher}. <p>
     *     This will request {@link Long#MAX_VALUE} from the {@link Subscription}.
     *
     * @param forEach {@link Consumer} to invoke for each {@link Subscriber#onNext(Object)}.
     *
     * @return {@link Cancellable} used to invoke {@link Subscription#cancel()} on the parameter of
     * {@link Subscriber#onSubscribe(Subscription)} for this {@link Publisher}.
     * */
    public final Cancellable forEach(Consumer<T> forEach) {
        ForEachSubscriber<T> subscriber = new ForEachSubscriber<>(forEach);
        subscribe(subscriber);
        return subscriber;
    }

    /**
     * Creates a new {@link Publisher} that emits {@code value} to its {@link Subscriber} and then {@link Subscriber#onComplete()}.
     *
     * @param value Value that the returned {@link Publisher} will emit.
     * @param <T> Type of items emitted by the returned {@link Publisher}.
     *
     * @return A new {@link Publisher} that emits {@code value} to its {@link Subscriber} and then {@link Subscriber#onComplete()}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/just.html">ReactiveX just operator.</a>
     */
    public static <T> Publisher<T> just(T value) {
        return new JustPublisher<>(value);
    }

    /**
     * Creates a new {@link Publisher} that emits all {@code values} to its {@link Subscriber} and then {@link Subscriber#onComplete()}.
     *
     * @param values Values that the returned {@link Publisher} will emit.
     * @param <T> Type of items emitted by the returned {@link Publisher}.
     *
     * @return A new {@link Publisher} that emits all {@code values} to its {@link Subscriber} and then {@link Subscriber#onComplete()}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/from.html">ReactiveX from operator.</a>
     */
    @SafeVarargs
    public static <T> Publisher<T> from(T... values) {
        return new FromArrayPublisher<>(values);
    }

    /**
     * Creates a new {@link Publisher} that completes when subscribed without emitting any item to its {@link Subscriber}.
     *
     * @param <T> Type of items emitted by the returned {@link Publisher}.
     * @return A new {@link Publisher} that completes when subscribed without emitting any item to its {@link Subscriber}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/empty-never-throw.html">ReactiveX empty operator.</a>
     */
    @SuppressWarnings("unchecked")
    public static <T> Publisher<T> empty() {
        return (Publisher<T>) EmptyPublisher.INSTANCE;
    }

    /**
     * Creates a new {@link Publisher} that never emits any item to its {@link Subscriber} and never call any terminal methods on it.
     *
     * @param <T> Type of items emitted by the returned {@link Publisher}.
     * @return A new {@link Publisher} that never emits any item to its {@link Subscriber} and never call any terminal methods on it.
     *
     * @see <a href="http://reactivex.io/documentation/operators/empty-never-throw.html">ReactiveX never operator.</a>
     */
    @SuppressWarnings("unchecked")
    public static <T> Publisher<T> never() {
        return (Publisher<T>) NeverPublisher.INSTANCE;
    }

    /**
     * Creates a new {@link Publisher} that terminates its {@link Subscriber} with an error without emitting any item to it.
     *
     * @param <T> Type of items emitted by the returned {@link Publisher}.
     * @param cause The {@link Throwable} that is used to terminate the {@link Subscriber}.
     * @return A new {@link Publisher} that terminates its {@link Subscriber} with an error without emitting any item to it.
     *
     * @see <a href="http://reactivex.io/documentation/operators/empty-never-throw.html">ReactiveX error operator.</a>
     */
    public static <T> Publisher<T> error(Throwable cause) {
        return new ErrorPublisher<>(cause);
    }

    /**
     * Defers creation of a {@link Publisher} till it is subscribed.
     *
     * @param publisherFactory {@link Supplier} to create a new {@link Publisher} for every call to {@link #subscribe(Subscriber)} to the returned {@link Publisher}.
     * @param <T> Type of items emitted by the returned {@link Publisher}.
     * @return A new {@link Publisher} that creates a new {@link Publisher} using {@code publisherFactory} for every call to {@link #subscribe(Subscriber)} and forwards all items
     * and terminal events from the newly created {@link Publisher} to its {@link Subscriber}.
     *
     * @see <a href="http://reactivex.io/documentation/operators/defer.html">ReactiveX defer operator.</a>
     */
    public static <T> Publisher<T> defer(Supplier<Publisher<T>> publisherFactory) {
        return new PublisherDefer<>(publisherFactory);
    }

    /**
     * Returns a {@link Publisher} that wraps a {@link org.reactivestreams.Publisher}.
     *
     * @param publisher {@link org.reactivestreams.Publisher} to wrap.
     * @param <T> Type of items emitted by the returned {@link Publisher}.
     * @return a new {@link Publisher} that wraps a {@link org.reactivestreams.Publisher}.
     */
    public static <T> Publisher<T> fromReactiveStreamsPublisher(org.reactivestreams.Publisher<T> publisher) {
        if (publisher instanceof Publisher) {
            return (Publisher<T>) publisher;
        }
        return new ReactiveStreamsPublisher<>(publisher);
    }

    /**
     * A group as emitted by {@link #groupBy(Function, int)} or its variants.
     *
     * @param <Key> Key for the group. If this is of type {@link QueueSizeProvider} new keys will use the value
     *             provided by {@link QueueSizeProvider#calculateMaxQueueSize(int)} to determine the maximum queue size
     *             for this group.
     * @param <T> Items emitted by this group.
     */
    public static final class Group<Key, T> {
        private final Key key;
        private final Publisher<T> value;

        Group(Key key, Publisher<T> value) {
            this.key = requireNonNull(key);
            this.value = requireNonNull(value);
        }

        /**
         * Returns the key for this group.
         *
         * @return Key for this group.
         */
        public Key getKey() {
            return key;
        }

        /**
         * Returns {@link Publisher} for this group. This {@link Publisher} will only allow a single {@link Subscriber}.
         *
         * @return {@link Publisher} for this group that only allows a single {@link Subscriber}.
         */
        public Publisher<T> getPublisher() {
            return value;
        }

        /**
         * Provide the maximum queue size to use for a particular {@link Group} key.
         */
        public interface QueueSizeProvider {
            /**
             * Calculate the maximum queue size for a particular {@link Group} key.
             * @param groupMaxQueueSize The maximum queue size for {@link Group} objects.
             * @return The maximum queue size for a particular {@link Group} key.
             */
            int calculateMaxQueueSize(int groupMaxQueueSize);
        }

        @Override
        public String toString() {
            return "Group{" +
                    "key=" + key +
                    ", value=" + value +
                    '}';
        }
    }
}
