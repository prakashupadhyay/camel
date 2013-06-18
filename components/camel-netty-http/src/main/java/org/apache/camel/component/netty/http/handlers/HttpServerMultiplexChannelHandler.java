/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.netty.http.handlers;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.camel.component.netty.http.NettyHttpConsumer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * A multiplex {@link org.apache.camel.component.netty.http.HttpServerPipelineFactory} which keeps a list of handlers, and delegates to the
 * target handler based on the http context path in the incoming request. This is used to allow to reuse
 * the same Netty consumer, allowing to have multiple routes on the same netty {@link org.jboss.netty.bootstrap.ServerBootstrap}
 */
public class HttpServerMultiplexChannelHandler extends SimpleChannelUpstreamHandler {

    // use NettyHttpConsumer as logger to make it easier to read the logs as this is part of the consumer
    private static final transient Logger LOG = LoggerFactory.getLogger(NettyHttpConsumer.class);
    private final ConcurrentMap<String, HttpServerChannelHandler> consumers = new ConcurrentHashMap<String, HttpServerChannelHandler>();
    private final String token;
    private final int len;

    public HttpServerMultiplexChannelHandler(int port) {
        this.token = ":" + port;
        this.len = token.length();
    }

    public void addConsumer(NettyHttpConsumer consumer) {
        String path = pathAsKey(consumer.getConfiguration().getPath());
        consumers.put(path, new HttpServerChannelHandler(consumer));
    }

    public void removeConsumer(NettyHttpConsumer consumer) {
        String path = pathAsKey(consumer.getConfiguration().getPath());
        consumers.remove(path);
    }

    /**
     * Number of active consumers.
     */
    public int consumers() {
        return consumers.size();
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent messageEvent) throws Exception {
        // store request, as this channel handler is created per pipeline
        HttpRequest request = (HttpRequest) messageEvent.getMessage();

        LOG.debug("Message received: {}", request);

        HttpServerChannelHandler handler = getHandler(request);
        if (handler != null) {
            // store handler as attachment
            ctx.setAttachment(handler);
            handler.messageReceived(ctx, messageEvent);
        } else {
            // this service is not available
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, SERVICE_UNAVAILABLE);
            messageEvent.getChannel().write(response);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        HttpServerChannelHandler handler = (HttpServerChannelHandler) ctx.getAttachment();
        if (handler != null) {
            handler.exceptionCaught(ctx, e);
        } else {
            throw new IllegalStateException("HttpServerChannelHandler not found as attachment. Cannot handle caught exception.", e.getCause());
        }
    }

    private HttpServerChannelHandler getHandler(HttpRequest request) {
        // need to strip out host and port etc, as we only need the context-path for matching
        String path = request.getUri();
        int idx = path.indexOf(token);
        if (idx > -1) {
            path = path.substring(idx + len);
        }

        // TODO: support matchOnUriPrefix

        // use the path as key to find the consumer handler to use
        path = pathAsKey(path);

        return consumers.get(path);
    }

    private static String pathAsKey(String path) {
        // cater for default path
        if (path == null || path.equals("/")) {
            path = "";
        }

        // strip out query parameters
        int idx = path.indexOf('?');
        if (idx > -1) {
            path = path.substring(0, idx);
        }

        // strip of ending /
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

}
