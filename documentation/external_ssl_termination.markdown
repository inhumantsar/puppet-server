External SSL termination with Puppet Server
====

In network configurations which require external SSL termination, there are a 
couple of differences between how you would configure Apache/Passenger and 
Puppet Server. 

  * Open `config.d/master.conf` and add  `allow-header-cert-info: true` to the 
    `webserver` config block. See [Puppet Server Configuration](./configuration.markdown) 
    for more info on the `master.conf` file. Without `allow-header-cert-info` set 
    to true, none of the HTTP headers described below will be recognized by 
    Puppet Server. Please note that if `allow-header-cert-info` is set to true, 
    Puppet Server is in an incredibly vulnerbale state, and extra caution should
    be taken to ensure it is absolutely not reachable by an untrusted network.

  * The `ssl_client_header` and `ssl_client_verify_header` options in the 
    `puppet.conf` file will now be enabled and work exactly as documented in
    Ruby Puppet. Please see the [Puppet Documentation](https://docs.puppetlabs.com/references/3.7.latest/configuration.html#sslclientheader)     
    for more detail on these. 
 
  * Remove the `ssl-port` and `ssl-host` settings from the
    `conf.d/webserver.conf` file and replace them with `port` and `host`
    settings. This will turn SSL off and Puppet Server will now use the HTTP
    protocol instead. See [Configuring the Webserver Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md)
    for more information on configuring the _webserver_ service.
    
  * Delivering the SSL certificate to Puppet is the biggest difference between
    Ruby Puppet and Puppet Server. Under Apache/Passenger, the client's
    certificate is delivered via an environment variable, passed between 
    Apache and the Ruby Puppet process. This is not an option in Puppet Server,
    so instead the certificate must be passed to Puppet Server via an HTTP
    header. The header is `X-Client-Cert` and must contain the client's 
    PEM-formatted (Base-64) certificate in a single URI-encoded string. Note 
    that URL encoding is not sufficient, all space characters must be encoded as 
    `%20` and not `+` characters. 
    
  * If the `allow-header-cert-info` setting is set to true, then, even if the
    web server is hosting an HTTPS connection, core Ruby Puppet application code
    will only use the client HTTP header values -- and not an SSL-layer client
    certificate -- to determine the client subject name, authentication status,
    and certificate.  This pertains to validation of the client via rules in
    the [auth.conf]
    (https://docs.puppetlabs.com/guides/rest_auth_conf.html) file and any
    [trusted facts]
    (https://docs.puppetlabs.com/puppet/latest/reference/lang_facts_and_builtin_vars.html#trusted-facts)
    extracted from certificate extensions.

    If the `client-auth` setting in the `webserver`
    config block is set to `need` or `want`, the Jetty webserver will validate
    the client certificate against a certificate authority store.  Only the
    SSL-layer client certificate -- and not a certificate in an `X-Client-Cert`
    header -- will be validated against the certificate authority store.
    