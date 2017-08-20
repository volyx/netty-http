/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelStateEvent;
import io.netty.channel.Channels;
import io.netty.channel.ExceptionEvent;
import io.netty.channel.MessageEvent;
import io.netty.channel.SimpleChannelUpstreamHandler;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpChunkAggregator;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RequestRouter that uses {@code HttpMethodHandler} to determine the http-handler method Signature of http request. It
 * uses this signature to dynamically configure the Netty Pipeline. Http Handler methods with return-type BodyConsumer
 * will be streamed , while other methods will use HttpChunkAggregator
 */

public class RequestRouter extends SimpleChannelUpstreamHandler {

  private static final Logger LOG = LoggerFactory.getLogger(HttpDispatcher.class);

  private final int chunkMemoryLimit;
  private final HttpResourceHandler httpMethodHandler;
  private final AtomicBoolean exceptionRaised;
  private final boolean enableSSL;

  private HttpMethodInfo methodInfo;

  public RequestRouter(HttpResourceHandler methodHandler, int chunkMemoryLimit, boolean enableSSL) {
    this.httpMethodHandler = methodHandler;
    this.chunkMemoryLimit = chunkMemoryLimit;
    this.exceptionRaised = new AtomicBoolean(false);
    this.enableSSL = enableSSL;
  }

  /**
   * If the HttpRequest is valid and handled it will be sent upstream, if it cannot be invoked
   * the response will be written back immediately.
   * @param ctx
   * @param e
   * @throws Exception
   */
  @Override
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
    if (exceptionRaised.get()) {
      return;
    }
    Object message = e.getMessage();
    if (!(message instanceof HttpRequest)) {
      super.messageReceived(ctx, e);
      return;
    }
    HttpRequest request = (HttpRequest) message;
    if (handleRequest(request, ctx.getChannel(), ctx)) {
      ctx.sendUpstream(e);
    }
  }

  @Override
  public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
    if (enableSSL) {
      // Get the SslHandler in the current pipeline.
      final SslHandler sslHandler = ctx.getPipeline().get(SslHandler.class);

      // Get notified when SSL handshake is done.
      ChannelFuture handshakeFuture = sslHandler.handshake();
      handshakeFuture.addListener(new SSLHandshakeListener());
    }
  }

  private class SSLHandshakeListener implements ChannelFutureListener {
    @Override
    public void operationComplete(ChannelFuture channelFuture) throws Exception {
      if (!channelFuture.isSuccess()) {
        channelFuture.addListener(ChannelFutureListener.CLOSE);
      }
    }
  }
  /**
   * If return type of the handler http method is BodyConsumer, remove "HttpChunkAggregator" class if it exits
   * Else add the HttpChunkAggregator to the pipeline if its not present already.
   */
  private boolean handleRequest(HttpRequest httpRequest, Channel channel, ChannelHandlerContext ctx) throws Exception {

    methodInfo = httpMethodHandler.getDestinationMethod(
      httpRequest, new BasicHttpResponder(channel, HttpHeaders.isKeepAlive(httpRequest)));

    if (methodInfo == null) {
      return false;
    }
    ctx.setAttachment(methodInfo);
    if (methodInfo.isStreaming()) {
      if (channel.getPipeline().get("aggregator") != null) {
        channel.getPipeline().remove("aggregator");
      }
    } else {
      if (channel.getPipeline().get("aggregator") == null) {
        channel.getPipeline().addAfter("router", "aggregator", new HttpChunkAggregator(chunkMemoryLimit));
      }
    }
    return true;
  }


  public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
    String exceptionMessage = "Exception caught in channel processing.";
    Throwable cause = e.getCause();
    if (exceptionRaised.compareAndSet(false, true)) {
      if (methodInfo != null) {
        LOG.error(exceptionMessage, cause);
        methodInfo.sendError(HttpResponseStatus.INTERNAL_SERVER_ERROR, cause);
        methodInfo = null;
      } else {
        ChannelFuture future = Channels.future(ctx.getChannel());
        future.addListener(ChannelFutureListener.CLOSE);
        if (cause instanceof HandlerException) {
          HttpResponse response = ((HandlerException) cause).createFailureResponse();
          // trace logs for user errors, error logs for internal server errors
          if (isUserError(response)) {
            LOG.trace(exceptionMessage, cause);
          } else {
            LOG.error(exceptionMessage, cause);
          }
          Channels.write(ctx, future, response);
        } else {
          LOG.error(exceptionMessage, cause);
          HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                                                          HttpResponseStatus.INTERNAL_SERVER_ERROR);
          Channels.write(ctx, future, response);
        }
      }
    } else {
      LOG.trace(exceptionMessage, cause);
    }
  }

  private boolean isUserError(HttpResponse response) {
    int code = response.getStatus().getCode();
    return code == HttpResponseStatus.BAD_REQUEST.getCode() || code == HttpResponseStatus.NOT_FOUND.getCode() ||
      code == HttpResponseStatus.METHOD_NOT_ALLOWED.getCode();
  }
}
