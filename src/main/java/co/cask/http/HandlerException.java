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

import com.google.common.base.Charsets;
import io.netty.buffer.Unpooled;
import io.netty.netty.buffer.ChannelBuffers;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 *Creating Http Response for Exception messages.
 */
final class HandlerException extends Exception {

  private final HttpResponseStatus failureStatus;
  private final String message;

  HandlerException(HttpResponseStatus failureStatus, String message) {
    super(message);
    this.failureStatus = failureStatus;
    this.message = message;
  }

  HandlerException(HttpResponseStatus failureStatus, String message, Throwable cause) {
    super(message, cause);
    this.failureStatus = failureStatus;
    this.message = message;
  }

  HttpResponse createFailureResponse() {
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, failureStatus);
    response.setContent(Unpooled.copiedBuffer(message, Charsets.UTF_8));
    return response;
  }
}
