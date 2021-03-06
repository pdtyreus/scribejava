package com.github.scribejava.core.oauth;

import com.ning.http.client.ProxyServer;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Future;
import com.github.scribejava.core.builder.api.DefaultApi10a;
import com.github.scribejava.core.model.AbstractRequest;
import com.github.scribejava.core.model.OAuthAsyncRequestCallback;
import com.github.scribejava.core.model.OAuthConfig;
import com.github.scribejava.core.model.OAuthConstants;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.OAuthRequestAsync;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Token;
import com.github.scribejava.core.model.Verifier;
import com.github.scribejava.core.services.Base64Encoder;
import com.github.scribejava.core.utils.MapUtils;

/**
 * OAuth 1.0a implementation of {@link OAuthService}
 *
 * @author Pablo Fernandez
 */
public class OAuth10aService extends OAuthService {

    private static final String VERSION = "1.0";
    private final DefaultApi10a api;

    /**
     * Default constructor
     *
     * @param api OAuth1.0a api information
     * @param config OAuth 1.0a configuration param object
     */
    public OAuth10aService(DefaultApi10a api, OAuthConfig config) {
        super(config);
        this.api = api;
    }

    /**
     * Retrieve the request token.
     *
     * @return request token
     */
    public Token getRequestToken() {
        final OAuthConfig config = getConfig();
        config.log("obtaining request token from " + api.getRequestTokenEndpoint());
        final OAuthRequest request = new OAuthRequest(api.getRequestTokenVerb(), api.getRequestTokenEndpoint(), this);

        config.log("setting oauth_callback to " + config.getCallback());
        request.addOAuthParameter(OAuthConstants.CALLBACK, config.getCallback());
        addOAuthParams(request, OAuthConstants.EMPTY_TOKEN);
        appendSignature(request);

        config.log("sending request...");
        final Response response = request.send();
        final String body = response.getBody();

        config.log("response status code: " + response.getCode());
        config.log("response body: " + body);
        return api.getRequestTokenExtractor().extract(body);
    }

    private void addOAuthParams(AbstractRequest request, Token token) {
        final OAuthConfig config = getConfig();
        request.addOAuthParameter(OAuthConstants.TIMESTAMP, api.getTimestampService().getTimestampInSeconds());
        request.addOAuthParameter(OAuthConstants.NONCE, api.getTimestampService().getNonce());
        request.addOAuthParameter(OAuthConstants.CONSUMER_KEY, config.getApiKey());
        request.addOAuthParameter(OAuthConstants.SIGN_METHOD, api.getSignatureService().getSignatureMethod());
        request.addOAuthParameter(OAuthConstants.VERSION, getVersion());
        if (config.hasScope()) {
            request.addOAuthParameter(OAuthConstants.SCOPE, config.getScope());
        }
        request.addOAuthParameter(OAuthConstants.SIGNATURE, getSignature(request, token));

        config.log("appended additional OAuth parameters: " + MapUtils.toString(request.getOauthParameters()));
    }

    public final Token getAccessToken(Token requestToken, Verifier verifier) {
        final OAuthConfig config = getConfig();
        config.log("obtaining access token from " + api.getAccessTokenEndpoint());
        final OAuthRequest request = new OAuthRequest(api.getAccessTokenVerb(), api.getAccessTokenEndpoint(), this);
        prepareAccessTokenRequest(request, requestToken, verifier);
        final Response response = request.send();
        return api.getAccessTokenExtractor().extract(response.getBody());
    }

    /**
     * Start the request to retrieve the access token. The optionally provided callback will be called with the Token
     * when it is available.
     *
     * @param requestToken request token (obtained previously or null)
     * @param verifier verifier code
     * @param callback optional callback
     * @return Future
     */
    public final Future<Token> getAccessTokenAsync(Token requestToken, Verifier verifier,
            OAuthAsyncRequestCallback<Token> callback) {
        return getAccessTokenAsync(requestToken, verifier, callback, null);
    }

    public final Future<Token> getAccessTokenAsync(Token requestToken, Verifier verifier,
            OAuthAsyncRequestCallback<Token> callback, ProxyServer proxyServer) {
        final OAuthConfig config = getConfig();
        config.log("async obtaining access token from " + api.getAccessTokenEndpoint());
        final OAuthRequestAsync request
                = new OAuthRequestAsync(api.getAccessTokenVerb(), api.getAccessTokenEndpoint(), this);
        prepareAccessTokenRequest(request, requestToken, verifier);
        return request.sendAsync(callback, new OAuthRequestAsync.ResponseConverter<Token>() {
            @Override
            public Token convert(com.ning.http.client.Response response) throws IOException {
                return getApi().getAccessTokenExtractor()
                        .extract(OAuthRequestAsync.RESPONSE_CONVERTER.convert(response).getBody());
            }
        }, proxyServer);
    }

    protected void prepareAccessTokenRequest(AbstractRequest request, Token requestToken, Verifier verifier) {
        final OAuthConfig config = getConfig();
        request.addOAuthParameter(OAuthConstants.TOKEN, requestToken.getToken());
        request.addOAuthParameter(OAuthConstants.VERIFIER, verifier.getValue());
        config.log("setting token to: " + requestToken + " and verifier to: " + verifier);
        addOAuthParams(request, requestToken);
        appendSignature(request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void signRequest(Token token, AbstractRequest request) {
        final OAuthConfig config = getConfig();
        config.log("signing request: " + request.getCompleteUrl());

        // Do not append the token if empty. This is for two legged OAuth calls.
        if (!token.isEmpty()) {
            request.addOAuthParameter(OAuthConstants.TOKEN, token.getToken());
        }
        config.log("setting token to: " + token);
        addOAuthParams(request, token);
        appendSignature(request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getVersion() {
        return VERSION;
    }

    /**
     * Returns the URL where you should redirect your users to authenticate your application.
     *
     * @param requestToken the request token you need to authorize
     * @return the URL where you should redirect your users
     */
    public String getAuthorizationUrl(Token requestToken) {
        return api.getAuthorizationUrl(requestToken);
    }

    private String getSignature(AbstractRequest request, Token token) {
        final OAuthConfig config = getConfig();
        config.log("generating signature...");
        config.log("using base64 encoder: " + Base64Encoder.type());
        final String baseString = api.getBaseStringExtractor().extract(request);
        final String signature = api.getSignatureService().getSignature(baseString, config.getApiSecret(), token.
                getSecret());

        config.log("base string is: " + baseString);
        config.log("signature is: " + signature);
        return signature;
    }

    private void appendSignature(AbstractRequest request) {
        final OAuthConfig config = getConfig();
        switch (config.getSignatureType()) {
            case Header:
                config.log("using Http Header signature");

                final String oauthHeader = api.getHeaderExtractor().extract(request);
                request.addHeader(OAuthConstants.HEADER, oauthHeader);
                break;
            case QueryString:
                config.log("using Querystring signature");

                for (Map.Entry<String, String> entry : request.getOauthParameters().entrySet()) {
                    request.addQuerystringParameter(entry.getKey(), entry.getValue());
                }
                break;
            default:
                throw new IllegalStateException("Unknown new Signature Type '" + config.getSignatureType() + "'.");
        }
    }

    public DefaultApi10a getApi() {
        return api;
    }
}
