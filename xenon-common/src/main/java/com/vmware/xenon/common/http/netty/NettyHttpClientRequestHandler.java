/*
 * Copyright (c) 2014-2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.xenon.common.http.netty;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import javax.net.ssl.SSLSession;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsciiString;

import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Operation.AuthorizationContext;
import com.vmware.xenon.common.Operation.OperationOption;
import com.vmware.xenon.common.ServerSentEvent;
import com.vmware.xenon.common.Service.Action;
import com.vmware.xenon.common.ServiceErrorResponse;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.common.serialization.ServerSentEventConverter;
import com.vmware.xenon.services.common.ServiceUriPaths;
import com.vmware.xenon.services.common.authn.AuthenticationConstants;

/**
 * Processes client requests on behalf of the HTTP listener and submits them to the service host or websocket client for
 * processing
 */
public class NettyHttpClientRequestHandler extends SimpleChannelInboundHandler<Object> {

    private static final String PROPERTY_NAME_PREFIX = Utils.PROPERTY_NAME_PREFIX +
            NettyHttpClientRequestHandler.class.getSimpleName();

    public static final String PROPERTY_NAME_DISABLE_HTTP_ONLY_AUTH_COOKIE = PROPERTY_NAME_PREFIX +
            "DISABLE_HTTP_ONLY_AUTH_COOKIE";

    public static final boolean DISABLE_HTTP_ONLY_AUTH_COOKIE = Boolean.getBoolean(
            PROPERTY_NAME_DISABLE_HTTP_ONLY_AUTH_COOKIE);

    private static final String ERROR_MSG_DECODING_FAILURE = "Failure decoding HTTP request";

    private final ServiceHost host;

    private final SslHandler sslHandler;

    private int responsePayloadSizeLimit;

    private NettyHttpListener listener;

    private boolean secureAuthCookie;

    public NettyHttpClientRequestHandler(ServiceHost host, NettyHttpListener listener,
            SslHandler sslHandler, int responsePayloadSizeLimit, boolean secureAuthCookie) {
        this.host = host;
        this.listener = listener;
        this.sslHandler = sslHandler;
        this.responsePayloadSizeLimit = responsePayloadSizeLimit;
        this.secureAuthCookie = secureAuthCookie;
    }

    @Override
    public boolean acceptInboundMessage(Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            return true;
        }
        return false;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.listener.addChannel(ctx.channel());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        this.listener.removeChannel(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        double startTime = 0;
        String requestedPath = null;

        if (this.host.isRequestLoggingEnabled()) {
            startTime = System.nanoTime();
        }

        Operation request = null;
        Integer streamId = null;
        try {

            // Start of request processing, initialize in-bound operation
            FullHttpRequest nettyRequest = (FullHttpRequest) msg;
            long expMicros = Utils.fromNowMicrosUtc(this.host.getOperationTimeoutMicros());

            request = Operation.createGet(null);
            request.setAction(Action.valueOf(nettyRequest.method().toString()))
                    .setExpiration(expMicros)
                    .forceRemote();

            // The streamId will be null for HTTP/1.1 connections, and valid for HTTP/2 connections
            streamId = nettyRequest.headers().getInt(
                    HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());

            if (streamId == null) {
                ctx.channel().attr(NettyChannelContext.OPERATION_KEY).set(request);
            }

            if (nettyRequest.decoderResult().isFailure()) {
                request.setStatusCode(Operation.STATUS_CODE_BAD_REQUEST).setKeepAlive(false);
                request.setBody(ServiceErrorResponse.create(
                        new IllegalArgumentException(ERROR_MSG_DECODING_FAILURE),
                        request.getStatusCode()));
                sendResponse(ctx, request, streamId, null, startTime);
                return;
            }

            parseRequestHeaders(ctx, request, nettyRequest);

            parseRequestUri(request, nettyRequest);
            requestedPath = request.getUri().getPath();

            decodeRequestBody(ctx, request, nettyRequest.content(), streamId,
                    requestedPath, startTime);
        } catch (Exception e) {
            this.host.log(Level.SEVERE, "Uncaught exception: %s", Utils.toString(e));
            if (request == null) {
                request = Operation.createGet(this.host.getUri());
            }
            int sc = Operation.STATUS_CODE_BAD_REQUEST;
            if (e instanceof URISyntaxException) {
                request.setUri(this.host.getUri());
            }
            request.setKeepAlive(false).setStatusCode(sc)
                    .setBodyNoCloning(ServiceErrorResponse.create(e, sc));
            sendResponse(ctx, request, streamId, requestedPath, startTime);
        }
    }

    private void parseRequestUri(Operation request, FullHttpRequest nettyRequest)
            throws URISyntaxException {
        URI targetUri = new URI(nettyRequest.uri());
        String decodedQuery = null;

        if (!request.isForwarded() && !request.isFromReplication()) {
            // do conservative parsing, normalization and decoding for non peer requests
            targetUri = targetUri.normalize();
            decodedQuery = targetUri.getQuery();
            if (decodedQuery != null && !decodedQuery.isEmpty()) {
                decodedQuery = QueryStringDecoder.decodeComponent(targetUri.getQuery());
            }
        }

        String query = decodedQuery == null ? targetUri.getRawQuery() : decodedQuery;
        URI hostUri = this.host.getUri();
        URI uri = new URI(hostUri.getScheme(), targetUri.getUserInfo(),
                ServiceHost.LOCAL_HOST,
                hostUri.getPort(), targetUri.getPath(), query, targetUri.getFragment());
        request.setUri(uri);

        if (!request.hasReferer() && request.isFromReplication()) {
            // we assume referrer is the same service, but from the remote node. Do not
            // bother with rewriting the URI with the remote host, at avoid allocations
            request.setReferer(request.getUri());
        }
    }

    private void decodeRequestBody(ChannelHandlerContext ctx, Operation request,
            ByteBuf content, Integer streamId, String originalPath, double startTime) throws Exception {
        if (!content.isReadable()) {
            // skip body decode, request had no body
            request.setContentLength(0);
            submitRequest(ctx, request, streamId, originalPath, startTime);
            return;
        }

        Utils.decodeBody(request, content.nioBuffer(), true);
        submitRequest(ctx, request, streamId, originalPath, startTime);
    }

    private void parseRequestHeaders(ChannelHandlerContext ctx, Operation request,
            HttpRequest nettyRequest) {

        HttpHeaders headers = nettyRequest.headers();
        boolean hasHeaders = !headers.isEmpty();

        String referer = getAndRemove(headers, HttpHeaderNames.REFERER);
        if (referer != null) {
            request.setReferer(referer);
        }

        if (!hasHeaders) {
            return;
        }

        request.setKeepAlive(HttpUtil.isKeepAlive(nettyRequest));
        if (HttpUtil.isContentLengthSet(nettyRequest)) {
            request.setContentLength(HttpUtil.getContentLength(nettyRequest));
            getAndRemove(headers, HttpHeaderNames.CONTENT_LENGTH);
        }

        String pragma = getAndRemove(headers, HttpHeaderNames.PRAGMA);
        if (Operation.PRAGMA_DIRECTIVE_REPLICATED.equals(pragma)) {
            // replication requests will have a single PRAGMA directive. Set the right
            // options and remove the header to avoid further allocations
            request.setFromReplication(true).setTargetReplicated(true);
        } else if (pragma != null) {
            request.addRequestHeader(Operation.PRAGMA_HEADER, pragma);
            if (pragma.contains(Operation.PRAGMA_DIRECTIVE_FORWARDED)) {
                request.toggleOption(OperationOption.FORWARDED, true);
            }
        }

        if (pragma != null && pragma.contains(Operation.PRAGMA_DIRECTIVE_REPLICATED)) {
            // synchronization requests will have additional directives, so check again here
            // if the request is replicated
            request.setFromReplication(true).setTargetReplicated(true);
        }

        request.setContextId(getAndRemove(headers, NettyHttpServiceClient.CONTEXT_ID_HEADER_ASCII));

        request.setTransactionId(
                getAndRemove(headers, NettyHttpServiceClient.TRANSACTION_ID_HEADER_ASCII));

        String contentType = getAndRemove(headers, HttpHeaderNames.CONTENT_TYPE);
        if (contentType != null) {
            request.setContentType(contentType);
        }

        String cookie = getAndRemove(headers, HttpHeaderNames.COOKIE);
        if (cookie != null) {
            request.setCookies(CookieJar.decodeCookies(cookie));
        }

        String host = getAndRemove(headers, HttpHeaderNames.HOST);

        for (Entry<String, String> h : headers) {
            String key = h.getKey();
            String value = h.getValue();
            if (Operation.STREAM_ID_HEADER.equals(key)) {
                continue;
            }
            if (Operation.HTTP2_SCHEME_HEADER.equals(key)) {
                continue;
            }

            request.addRequestHeader(key, value);
        }

        if (host != null) {
            request.addRequestHeader(Operation.HOST_HEADER, host);
        }

        if (this.sslHandler == null) {
            return;
        }
        try {
            if (this.sslHandler.engine().getWantClientAuth()
                    || this.sslHandler.engine().getNeedClientAuth()) {
                SSLSession session = this.sslHandler.engine().getSession();
                request.setPeerCertificates(session.getPeerPrincipal(),
                        session.getPeerCertificateChain());
            }
        } catch (Exception e) {
            this.host.log(Level.WARNING, "Failed to get peer principal " + Utils.toString(e));
        }
    }

    private String getAndRemove(HttpHeaders headers, AsciiString headerName) {
        String headerValue = headers.get(headerName);
        headers.remove(headerName);
        return headerValue;
    }

    private void submitRequest(ChannelHandlerContext ctx, Operation request,
            Integer streamId, String originalPath, double startTime) {
        AtomicBoolean isStreamingEnabled = new AtomicBoolean();
        request.nestCompletion((o, e) -> {
            if (!isStreamingEnabled.get()) {
                request.setBodyNoCloning(o.getBodyRaw());
                sendResponse(ctx, request, streamId, originalPath, startTime);
            } else {
                if (e != null) {
                    ServerSentEvent errorEvent = new ServerSentEvent().setEvent(ServerSentEvent.EVENT_TYPE_ERROR)
                            .setData(Utils.toJson(o.getBody(ServiceErrorResponse.class)));
                    request.sendServerSentEvent(errorEvent);
                }
                concludeRequest(ctx, request, true);
            }
        });
        request.nestHeadersReceivedHandler(ignore -> {
            if (!isStreamingEnabled.getAndSet(true)) {
                HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(request.getStatusCode()));
                this.addCommonHeaders(response, request, streamId);
                ctx.writeAndFlush(response);
            }
        });
        request.nestServerSentEventHandler(event -> {
            if (isStreamingEnabled.get()) {
                byte[] data = ServerSentEventConverter.INSTANCE.serialize(event).getBytes(ServerSentEventConverter.ENCODING_CHARSET);
                ByteBuf byteBuf = Unpooled.wrappedBuffer(data);
                ctx.writeAndFlush(byteBuf);
            } else {
                throw new RuntimeException("Call to startEventStream() or sendHeaders() is necessary to enable streaming!");
            }
        });

        request.toggleOption(OperationOption.CLONING_DISABLED, true);

        if (!request.hasReferer()) {
            setRefererFromSocketContext(ctx, request);
        }

        this.host.handleRequest(null, request);
    }

    private void sendResponse(ChannelHandlerContext ctx, Operation request,
            Integer streamId, String originalPath, double startTime) {
        try {
            applyRateLimit(ctx, request);
            writeResponseUnsafe(ctx, request, streamId, originalPath, startTime);
        } catch (Exception e1) {
            this.host.log(Level.SEVERE, "%s", Utils.toString(e1));
        }
    }

    private void applyRateLimit(ChannelHandlerContext ctx, Operation request) {
        if (!request.hasOption(OperationOption.RATE_LIMITED)) {
            return;
        }

        this.listener.pauseChannel(ctx.channel());
    }

    private void writeResponseUnsafe(ChannelHandlerContext ctx, Operation request,
            Integer streamId, String originalPath, double startTime) {
        ByteBuf bodyBuffer = null;
        FullHttpResponse response;

        try {
            byte[] data = Utils.encodeBody(request, false);

            // if some service returns a response that is greater than the maximum allowed size,
            // we return an INTERNAL_SERVER_ERROR.
            if (request.getContentLength() > this.responsePayloadSizeLimit) {
                String errorMessage = "Content-Length " + request.getContentLength()
                        + " is greater than max size allowed " + this.responsePayloadSizeLimit;
                this.host.log(Level.SEVERE, errorMessage);
                writeInternalServerError(ctx, request, streamId, errorMessage, originalPath, startTime);
                return;
            }
            if (data != null) {
                bodyBuffer = Unpooled.wrappedBuffer(data);
            }
        } catch (Exception e1) {
            // Note that this is a program logic error - some service isn't properly checking or setting Content-Type
            this.host.log(Level.SEVERE, "Error encoding body: %s", Utils.toString(e1));
            writeInternalServerError(ctx, request, streamId,
                    "Error encoding body: " + e1.getMessage(), originalPath, startTime);
            return;
        }

        if (bodyBuffer == null || request.getStatusCode() == Operation.STATUS_CODE_NOT_MODIFIED) {
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(request.getStatusCode()), false, false);
        } else {
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(request.getStatusCode()), bodyBuffer, false, false);
        }

        this.addCommonHeaders(response, request, streamId);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
                response.content().readableBytes());
        writeResponse(ctx, request, response, streamId, originalPath, startTime);
    }

    private void addCommonHeaders(HttpResponse response, Operation request, Integer streamId) {
        if (streamId != null) {
            // This is the stream ID from the incoming request: we need to use it for our
            // response so the client knows this is the response. If we don't set the stream
            // ID, Netty assigns a new, unused stream, which would be bad.
            response.headers().setInt(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(),
                    streamId);
        }

        // remove optional HTTP/2 stream weight header, all our streams are equal
        request.getAndRemoveResponseHeaderAsIs(Operation.STREAM_WEIGHT_HEADER);

        response.headers().set(HttpHeaderNames.CONTENT_TYPE,
                request.getContentType());

        if (request.hasResponseHeaders()) {
            // add any other custom headers associated with operation
            for (Entry<String, String> nameValue : request.getResponseHeaders().entrySet()) {
                response.headers().set(nameValue.getKey(), nameValue.getValue());
            }
        }

        // Add auth token to response if authorization context
        AuthorizationContext authorizationContext = request.getAuthorizationContext();
        if (authorizationContext != null && authorizationContext.shouldPropagateToClient()) {
            String token = authorizationContext.getToken();

            // The x-xenon-auth-token header is our preferred style
            response.headers().add(Operation.REQUEST_AUTH_TOKEN_HEADER, token);

            // Client can also use the cookie if they prefer
            Cookie tokenCookie = new DefaultCookie(
                    AuthenticationConstants.REQUEST_AUTH_TOKEN_COOKIE, token);

            // Add path qualifier, cookie applies everywhere
            tokenCookie.setPath("/");

            // Add a Max-Age qualifier if an expiration is set in the Claims object
            Long expirationTime = authorizationContext.getClaims().getExpirationTime();
            if (expirationTime != null) {
                long nowSeconds = TimeUnit.MICROSECONDS.toSeconds(Utils.getSystemNowMicrosUtc());
                long maxAge = expirationTime - nowSeconds;
                tokenCookie.setMaxAge(maxAge > 0 ? maxAge : 0);
            }

            // Add an HTTP-only qualifier unless the caller has specified otherwise
            if (!DISABLE_HTTP_ONLY_AUTH_COOKIE) {
                tokenCookie.setHttpOnly(true);
            }

            // Toggle the secure qualifier according to the caller's specification.
            tokenCookie.setSecure(this.secureAuthCookie);

            // Encode the cookie and add the corresponding header to the HTTP response.
            String tokenCookieString = ServerCookieEncoder.LAX.encode(tokenCookie);
            response.headers().add(Operation.SET_COOKIE_HEADER, tokenCookieString);
        }
    }

    private void writeInternalServerError(ChannelHandlerContext ctx, Operation request,
            Integer streamId, String err, String originalPath, double startTime) {
        byte[] data;
        try {
            data = err.getBytes(Utils.CHARSET);
        } catch (UnsupportedEncodingException ueex) {
            this.exceptionCaught(ctx, ueex);
            return;
        }

        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.INTERNAL_SERVER_ERROR,
                Unpooled.wrappedBuffer(data), false, false);
        if (streamId != null) {
            response.headers().setInt(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(),
                    streamId);
        }
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, Operation.MEDIA_TYPE_TEXT_HTML);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
                response.content().readableBytes());
        writeResponse(ctx, request, response, streamId, originalPath, startTime);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Operation op = ctx.channel().attr(NettyChannelContext.OPERATION_KEY).getAndSet(null);
        if (op != null) {
            this.host.log(Level.SEVERE,
                    "HTTP/1.1 listener channel exception: %s, in progress op: %s",
                    cause.getMessage(), op.toString());
        } else {
            // This case may be hit for HTTP/2 connections, which do not have
            // a single set of operations associated with them.
            this.host.log(Level.SEVERE, "Listener channel exception: %s",
                    cause.getMessage());
        }
        ctx.close();
    }

    private void setRefererFromSocketContext(ChannelHandlerContext ctx, Operation request) {
        try {
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            String path = NettyHttpListener.UNKNOWN_CLIENT_REFERER_PATH;
            request.setReferer(UriUtils.buildUri(
                    this.sslHandler != null ? "https" : "http",
                    remote.getHostString(),
                    remote.getPort(),
                    path,
                    null));
        } catch (Exception e) {
            this.host.log(Level.SEVERE, "%s", Utils.toString(e));
        }
    }

    private void writeResponse(ChannelHandlerContext ctx, Operation request,
            FullHttpResponse response, Integer streamId, String originalPath, double startTime) {
        boolean isClose = !request.isKeepAlive() || response == null;
        Object rsp = Unpooled.EMPTY_BUFFER;
        if (response != null) {
            AsciiString v = isClose ? HttpHeaderValues.CLOSE : HttpHeaderValues.KEEP_ALIVE;
            response.headers().set(HttpHeaderNames.CONNECTION, v);
            rsp = response;
        }

        ctx.channel().attr(NettyChannelContext.OPERATION_KEY).set(null);
        ChannelFuture future = ctx.writeAndFlush(rsp);

        if (this.host.isRequestLoggingEnabled()) {
            boolean avoidLogging =
                    request.hasAnyPragmaDirective(this.host.getSkipLoggingPragmaDirectives()) ||
                    (this.host.getRequestLoggingInfo().skipGossipRequests &&
                            request.getUri().getPath().contains(ServiceUriPaths.NODE_GROUP_FACTORY));

            if (!avoidLogging) {
                double totalTimeMillis = (System.nanoTime() - startTime) / 1000000;
                this.host.log(Level.INFO, "%s %s %s %s %d %d %.2fms",
                        ctx.channel().remoteAddress(), request.getAction(), originalPath,
                        streamId != null ? "HTTP/2" : "HTTP/1.1", request.getStatusCode(),
                        request.getContentLength(), totalTimeMillis);
            }
        }

        if (isClose) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void concludeRequest(ChannelHandlerContext ctx, Operation request, boolean forceClose) {
        boolean isClose = !request.isKeepAlive() || forceClose;
        ctx.channel().attr(NettyChannelContext.OPERATION_KEY).set(null);
        ChannelFuture future = ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
        if (isClose) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
