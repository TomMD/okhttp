/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.dnsoverhttps;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import javax.annotation.Nullable;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.platform.Platform;
import okio.ByteString;

/**
 * DNS over HTTPS implementation.
 *
 * Implementation of https://tools.ietf.org/html/draft-ietf-doh-dns-over-https-07
 */
public class DnsOverHttps implements Dns {
  public static final MediaType DNS_MESSAGE = MediaType.parse("application/dns-message");
  public static final MediaType UDPWIREFORMAT = MediaType.parse("application/dns-udpwireformat");
  private final OkHttpClient client;
  private final HttpUrl url;
  private final boolean includeIPv6;
  private final boolean post;
  private final MediaType contentType;

  public DnsOverHttps(OkHttpClient client, HttpUrl url,
      @Nullable Dns bootstrapDns, boolean includeIPv6, String method, MediaType contentType) {
    this.client = bootstrapDns != null ? client.newBuilder().dns(bootstrapDns).build() : client;
    this.url = url;
    this.includeIPv6 = includeIPv6;
    if (!method.equals("GET") && !method.equals("POST")) {
      throw new UnsupportedOperationException("Only GET and POST Supported");
    }
    this.post = method.equals("POST");
    this.contentType = contentType;
  }

  public HttpUrl getUrl() {
    return url;
  }

  @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    try {
      //System.out.println("Host: " + hostname);

      ByteString query = DnsRecordCodec.encodeQuery(hostname, includeIPv6);

      Request request = buildRequest(query);
      Response response = client.newCall(request).execute();

      // TODO reenable (currently noisy with test servers)
      if (response.cacheResponse() == null && response.protocol() != Protocol.HTTP_2) {
        Platform.get().log(Platform.WARN, "Incorrect protocol: " + response.protocol(), null);
      }

      // TODO remove (temporary info only currently)
      if (client.cache() != null && !post && response.cacheResponse() == null) {
        Platform.get().log(Platform.INFO, "DNS missed cache: " + hostname, null);
      }

      try {
        if (!response.isSuccessful()) {
          throw new IOException("response: " + response.code() + " " + response.message());
        }

        ByteString responseBytes = response.body().source().readByteString();

        //System.out.println("Response: " + responseBytes.hex());

        List<InetAddress> results = DnsRecordCodec.decodeAnswers(hostname, responseBytes);

        return results;
      } finally {
        response.close();
      }
    } catch (UnknownHostException uhe) {
      throw uhe;
    } catch (Exception e) {
      UnknownHostException unknownHostException = new UnknownHostException(hostname);
      unknownHostException.initCause(e);
      throw unknownHostException;
    }
  }

  private Request buildRequest(ByteString query) {
    Request.Builder builder;

    if (post) {
      builder = new Request.Builder().url(url);

      builder.post(RequestBody.create(contentType, query));
    } else {
      String encoded = query.base64Url().replace("=", "");

      //System.out.println("Query: " + encoded);

      HttpUrl requestUrl = url.newBuilder().addQueryParameter("dns", encoded).build();

      builder = new Request.Builder().url(requestUrl);
    }

    //System.out.println("URL: " + requestUrl);

    builder.header("Accept", contentType.toString());

    return builder.build();
  }
}
