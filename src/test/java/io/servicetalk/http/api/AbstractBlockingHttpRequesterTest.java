/*
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
package io.servicetalk.http.api;

import io.servicetalk.concurrent.api.BlockingIterable;
import io.servicetalk.concurrent.api.BlockingIterator;
import io.servicetalk.concurrent.api.PublisherRule;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.concurrent.internal.ServiceTalkTestTimeout;
import io.servicetalk.transport.api.ConnectionContext;
import io.servicetalk.transport.api.ExecutionContext;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import static io.servicetalk.concurrent.api.Executors.immediate;
import static io.servicetalk.concurrent.api.Publisher.just;
import static io.servicetalk.concurrent.api.Single.success;
import static io.servicetalk.concurrent.internal.Await.awaitIndefinitely;
import static io.servicetalk.http.api.HttpProtocolVersions.HTTP_1_1;
import static io.servicetalk.http.api.HttpRequestMethods.GET;
import static io.servicetalk.http.api.HttpResponseStatuses.OK;
import static io.servicetalk.http.api.HttpResponses.newResponse;
import static io.servicetalk.http.api.TestUtils.chunkFromString;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Collections.singleton;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public abstract class AbstractBlockingHttpRequesterTest {
    @Rule
    public final ServiceTalkTestTimeout timeout = new ServiceTalkTestTimeout();
    @Mock
    private ExecutionContext mockExecutionCtx;
    @Mock
    private ConnectionContext mockCtx;
    @Rule
    public final PublisherRule<HttpPayloadChunk> publisherRule = new PublisherRule<>();
    @Mock
    private BlockingIterable<HttpPayloadChunk> mockIterable;
    @Mock
    private BlockingIterator<HttpPayloadChunk> mockIterator;

    protected abstract <T extends HttpRequester & TestHttpRequester>
        T newAsyncRequester(ExecutionContext executionContext,
                            Function<HttpRequest<HttpPayloadChunk>, Single<HttpResponse<HttpPayloadChunk>>> doRequest);

    protected abstract <T extends BlockingHttpRequester & TestHttpRequester>
        T newBlockingRequester(ExecutionContext executionContext,
                               Function<BlockingHttpRequest<HttpPayloadChunk>,
                                        BlockingHttpResponse<HttpPayloadChunk>> doRequest);

    protected interface TestHttpRequester {
        boolean isClosed();
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mockExecutionCtx.getExecutor()).thenReturn(immediate());
        when(mockCtx.getExecutionContext()).thenReturn(mockExecutionCtx);
        when(mockIterable.iterator()).thenReturn(mockIterator);
    }

    @Test
    public void asyncToSyncNoPayload() throws Exception {
        HttpRequester asyncRequester = newAsyncRequester(mockExecutionCtx,
                req -> success(newResponse(HTTP_1_1, OK)));
        BlockingHttpRequester syncRequester = asyncRequester.asBlockingRequester();
        BlockingHttpResponse<HttpPayloadChunk> syncResponse = syncRequester.request(
                BlockingHttpRequests.newRequest(HTTP_1_1, GET, "/"));
        assertEquals(HTTP_1_1, syncResponse.getVersion());
        assertEquals(OK, syncResponse.getStatus());
    }

    @Test
    public void asyncToSyncWithPayload() throws Exception {
        HttpRequester asyncRequester = newAsyncRequester(mockExecutionCtx,
                req -> success(newResponse(HTTP_1_1, OK, just(chunkFromString("hello")))));
        BlockingHttpRequester syncRequester = asyncRequester.asBlockingRequester();
        BlockingHttpResponse<HttpPayloadChunk> syncResponse = syncRequester.request(
                BlockingHttpRequests.newRequest(HTTP_1_1, GET, "/"));
        assertEquals(HTTP_1_1, syncResponse.getVersion());
        assertEquals(OK, syncResponse.getStatus());
        BlockingIterator<HttpPayloadChunk> iterator = syncResponse.getPayloadBody().iterator();
        assertTrue(iterator.hasNext());
        assertEquals(chunkFromString("hello"), iterator.next());
        assertFalse(iterator.hasNext());
    }

    @Test
    public void asyncToSyncClose() throws Exception {
        HttpRequester asyncRequester = newAsyncRequester(mockExecutionCtx,
                req -> Single.error(new IllegalStateException("shouldn't be called!")));
        BlockingHttpRequester syncRequester = asyncRequester.asBlockingRequester();
        syncRequester.close();
        assertTrue(((TestHttpRequester) asyncRequester).isClosed());
    }

    @Test
    public void asyncToSyncCancelPropagated() throws Exception {
        HttpRequester asyncRequester = newAsyncRequester(mockExecutionCtx,
                req -> success(newResponse(HTTP_1_1, OK, publisherRule.getPublisher())));
        BlockingHttpRequester syncRequester = asyncRequester.asBlockingRequester();
        BlockingHttpResponse<HttpPayloadChunk> syncResponse = syncRequester.request(
                BlockingHttpRequests.newRequest(HTTP_1_1, GET, "/"));
        assertEquals(HTTP_1_1, syncResponse.getVersion());
        assertEquals(OK, syncResponse.getStatus());
        BlockingIterator<HttpPayloadChunk> iterator = syncResponse.getPayloadBody().iterator();
        publisherRule.sendItems(chunkFromString("hello"));
        assertTrue(iterator.hasNext());
        iterator.close();
        publisherRule.verifyCancelled();
    }

    @Test
    public void syncToAsyncNoPayload() throws Exception {
        BlockingHttpRequester syncRequester = newBlockingRequester(mockExecutionCtx,
                req -> BlockingHttpResponses.newResponse(HTTP_1_1, OK));
        HttpRequester asyncRequester = syncRequester.asAsynchronousRequester();
        HttpResponse<HttpPayloadChunk> asyncResponse = awaitIndefinitely(asyncRequester.request(
                HttpRequests.newRequest(HTTP_1_1, GET, "/")));
        assertNotNull(asyncResponse);
        assertEquals(HTTP_1_1, asyncResponse.getVersion());
        assertEquals(OK, asyncResponse.getStatus());
    }

    @Test
    public void syncToAsyncWithPayload() throws Exception {
        BlockingHttpRequester syncRequester = newBlockingRequester(mockExecutionCtx,
                req -> BlockingHttpResponses.newResponse(HTTP_1_1, OK, singleton(chunkFromString("hello"))));
        HttpRequester asyncRequester = syncRequester.asAsynchronousRequester();
        HttpResponse<HttpPayloadChunk> asyncResponse = awaitIndefinitely(asyncRequester.request(
                HttpRequests.newRequest(HTTP_1_1, GET, "/")));
        assertNotNull(asyncResponse);
        assertEquals(HTTP_1_1, asyncResponse.getVersion());
        assertEquals(OK, asyncResponse.getStatus());
        assertEquals("hello", awaitIndefinitely(asyncResponse.getPayloadBody()
                .reduce(() -> "", (acc, next) -> acc + next.getContent().toString(US_ASCII))));
    }

    @Test
    public void syncToAsyncClose() throws Exception {
        BlockingHttpRequester syncRequester = newBlockingRequester(mockExecutionCtx, req -> {
            throw new IllegalStateException("shouldn't be called!");
        });
        HttpRequester asyncRequester = syncRequester.asAsynchronousRequester();
        awaitIndefinitely(asyncRequester.closeAsync());
        assertTrue(((TestHttpRequester) syncRequester).isClosed());
    }

    @Test
    public void syncToAsyncCancelPropagated() throws Exception {
        BlockingHttpRequester syncRequester = newBlockingRequester(mockExecutionCtx, req ->
                BlockingHttpResponses.newResponse(HTTP_1_1, OK, mockIterable));
        HttpRequester asyncRequester = syncRequester.asAsynchronousRequester();
        HttpResponse<HttpPayloadChunk> asyncResponse = awaitIndefinitely(asyncRequester.request(
                HttpRequests.newRequest(HTTP_1_1, GET, "/")));
        assertNotNull(asyncResponse);
        CountDownLatch latch = new CountDownLatch(1);
        asyncResponse.getPayloadBody().subscribe(new Subscriber<HttpPayloadChunk>() {
            @Override
            public void onSubscribe(final Subscription s) {
                s.cancel();
                latch.countDown();
            }

            @Override
            public void onNext(final HttpPayloadChunk s) {
            }

            @Override
            public void onError(final Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });
        latch.await();
        verify(mockIterator).close();
    }
}
