/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.rs.security.httpsignature.filters;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.WriterInterceptor;
import javax.ws.rs.ext.WriterInterceptorContext;

import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.jaxrs.utils.HttpUtils;
import org.apache.cxf.jaxrs.utils.JAXRSUtils;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.PhaseInterceptorChain;
import org.apache.cxf.rs.security.httpsignature.HTTPSignatureConstants;
import org.apache.cxf.rs.security.httpsignature.utils.DefaultSignatureConstants;
import org.apache.cxf.rs.security.httpsignature.utils.SignatureHeaderUtils;

/**
 * RS WriterInterceptor which adds digests of the body and signing of headers. It will not be invoked for an
 * empty request body (e.g. a GET request). In that case use one of the filters.
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class CreateSignatureInterceptor extends AbstractSignatureOutFilter implements WriterInterceptor {
    private static final String DIGEST_HEADER_NAME = "Digest";
    private String digestAlgorithmName;

    @Context
    private UriInfo uriInfo;

    private boolean addDigest = true;

    private boolean shouldAddDigest(WriterInterceptorContext context) {
        return addDigest && null != context.getEntity()
            && context.getHeaders().keySet().stream().noneMatch(DIGEST_HEADER_NAME::equalsIgnoreCase);
    }

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
        // skip digest if already set or we actually don't have a body
        if (shouldAddDigest(context)) {
            addDigest(context);
        } else {
            sign(context);
            context.proceed();
        }
    }

    protected void sign(WriterInterceptorContext writerInterceptorContext) {
        String method = HttpUtils.getProtocolHeader(JAXRSUtils.getCurrentMessage(),
            Message.HTTP_REQUEST_METHOD, "");
        performSignature(writerInterceptorContext.getHeaders(), uriInfo.getRequestUri().getPath(), method);
    }

    private void addDigest(WriterInterceptorContext context) throws IOException {
        // make sure we have all content
        OutputStream originalOutputStream = context.getOutputStream();
        CachedOutputStream cachedOutputStream = new CachedOutputStream();
        context.setOutputStream(cachedOutputStream);

        context.proceed();
        cachedOutputStream.flush();

        // then digest using requested encoding
        String encoding = context.getMediaType().getParameters()
            .getOrDefault(MediaType.CHARSET_PARAMETER, StandardCharsets.UTF_8.toString());

        String digestAlgorithm = digestAlgorithmName;
        if (digestAlgorithm == null) {
            Message m = PhaseInterceptorChain.getCurrentMessage();
            digestAlgorithm =
                (String)m.getContextualProperty(HTTPSignatureConstants.RSSEC_HTTP_SIGNATURE_DIGEST_ALGORITHM);
            if (digestAlgorithm == null) {
                digestAlgorithm = DefaultSignatureConstants.DIGEST_ALGORITHM;
            }
        }

        // not so nice - would be better to have a stream
        String digest = SignatureHeaderUtils.createDigestHeader(
            new String(cachedOutputStream.getBytes(), encoding), digestAlgorithm);

        // add header
        context.getHeaders().add(DIGEST_HEADER_NAME, digest);
        sign(context);

        // write the contents
        context.setOutputStream(originalOutputStream);
        IOUtils.copy(cachedOutputStream.getInputStream(), originalOutputStream);
    }

    public String getDigestAlgorithmName() {
        return digestAlgorithmName;
    }

    public void setDigestAlgorithmName(String digestAlgorithmName) {
        this.digestAlgorithmName = digestAlgorithmName;
    }

    public boolean isAddDigest() {
        return addDigest;
    }

    public void setAddDigest(boolean addDigest) {
        this.addDigest = addDigest;
    }

}
