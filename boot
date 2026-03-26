#!/bin/bash

set -e

# Send syslog messages to stderr, optionally relaying them to another socket
# for postfix-exporter to take a look at
if [ -z "$SOCKETEE_RELAY_SOCKET" ]; then
	/usr/bin/socat UNIX-RECV:/dev/log,mode=0666 stderr &
else
	/usr/local/bin/socketee /dev/log "$SOCKETEE_RELAY_SOCKET" &
fi

echo "Operating environment:" >&2
env >&2

ruby -rjson -e "File.write('/etc/postfix/mail-receiver-environment.json', ENV.to_hash.to_json)"

if [ -z "$MAIL_DOMAIN" ]; then
	echo "FATAL ERROR: MAIL_DOMAIN env var is not set." >&2
	exit 1
fi

/usr/sbin/postconf -e relay_domains="$MAIL_DOMAIN"
rm -f /etc/postfix/transport
for d in $MAIL_DOMAIN; do
	echo "Delivering mail sent to $d to Discourse" >&2
	/bin/echo "$d discourse:" >>/etc/postfix/transport
done
/usr/sbin/postmap /etc/postfix/transport

# Validate config and generate per-domain environment files.
#
# Per-domain overrides use the domain name with dots and hyphens replaced by
# underscores, uppercased, appended to the var name.  For example, for domain
# "site1.com" set DISCOURSE_BASE_URL_SITE1_COM, DISCOURSE_API_KEY_SITE1_COM,
# and DISCOURSE_API_USERNAME_SITE1_COM.  Any var not set at the domain level
# falls back to the global value.
ruby -rjson << 'RUBY' || exit 1
  env = ENV.to_hash
  domains = ENV['MAIL_DOMAIN'].split
  errors = []

  domains.each do |domain|
    suffix = domain.tr('.-', '_').upcase
    domain_env = env.dup

    %w[DISCOURSE_BASE_URL DISCOURSE_MAIL_ENDPOINT DISCOURSE_API_KEY DISCOURSE_API_USERNAME BLACKLISTED_SENDER_DOMAINS].each do |key|
      domain_key = "#{key}_#{suffix}"
      domain_env[key] = env[domain_key] if env[domain_key]
    end

    %w[DISCOURSE_API_KEY DISCOURSE_API_USERNAME].each do |key|
      errors << "#{domain}: #{key} is not set" unless domain_env[key]
    end
    unless domain_env['DISCOURSE_BASE_URL'] || domain_env['DISCOURSE_MAIL_ENDPOINT']
      errors << "#{domain}: DISCOURSE_BASE_URL or DISCOURSE_MAIL_ENDPOINT is not set"
    end

    File.write("/etc/postfix/mail-receiver-environment-#{domain}.json", domain_env.to_json)
    STDERR.puts "Generated config for #{domain}"
  end

  unless errors.empty?
    errors.each { |e| STDERR.puts "FATAL ERROR: #{e}" }
    exit 1
  end
RUBY

# Generic postfix config setting code... bashers gonna bash.
for envvar in $(compgen -v); do
	if [[ "$envvar" =~ ^POSTCONF_ ]]; then
		varname="${envvar/POSTCONF_/}"
		echo "Setting $varname to '${!envvar}'" >&2
		/usr/sbin/postconf -e $varname="${!envvar}"
	fi
done

if [ "$INCLUDE_DMARC" = "true" ]; then
  echo "Starting OpenDKIM..." >&2
  adduser postfix opendkim #ensure postfix is part of opendkim group so it can access the socket
  /usr/sbin/opendkim -x /etc/opendkim.conf

  echo "Starting OpenDMARC..." >&2
  adduser postfix opendmarc #ensure postfix is part of opendmarc group so it can access the socket
  /usr/sbin/opendmarc -c /etc/opendmarc.conf
fi

# TLS via Let's Encrypt (Cloudflare DNS-01 challenge).
# Set MAIL_HOSTNAME, LETSENCRYPT_EMAIL, and CLOUDFLARE_API_TOKEN to enable.
if [ -n "$MAIL_HOSTNAME" ]; then
	if [ -z "$LETSENCRYPT_EMAIL" ]; then
		echo "FATAL ERROR: LETSENCRYPT_EMAIL is required when MAIL_HOSTNAME is set." >&2
		exit 1
	fi
	if [ -z "$CLOUDFLARE_API_TOKEN" ]; then
		echo "FATAL ERROR: CLOUDFLARE_API_TOKEN is required when MAIL_HOSTNAME is set." >&2
		exit 1
	fi

	/usr/sbin/postconf -e myhostname="$MAIL_HOSTNAME"

	# Write Cloudflare credentials (needed for both issuance and renewal)
	printf 'dns_cloudflare_api_token = %s\n' "$CLOUDFLARE_API_TOKEN" > /etc/cloudflare.ini
	chmod 600 /etc/cloudflare.ini

	if [ ! -f "/etc/letsencrypt/live/$MAIL_HOSTNAME/fullchain.pem" ]; then
		echo "Obtaining Let's Encrypt certificate for $MAIL_HOSTNAME..." >&2
		certbot certonly \
			--dns-cloudflare \
			--dns-cloudflare-credentials /etc/cloudflare.ini \
			--dns-cloudflare-propagation-seconds 60 \
			--non-interactive \
			--agree-tos \
			-m "$LETSENCRYPT_EMAIL" \
			-d "$MAIL_HOSTNAME" >&2
	fi

	/usr/sbin/postconf -e smtpd_tls_cert_file="/etc/letsencrypt/live/$MAIL_HOSTNAME/fullchain.pem"
	/usr/sbin/postconf -e smtpd_tls_key_file="/etc/letsencrypt/live/$MAIL_HOSTNAME/privkey.pem"
	/usr/sbin/postconf -e smtpd_tls_security_level=may
	/usr/sbin/postconf -e smtpd_tls_loglevel=1

	# Check for renewal daily in the background
	(while true; do
		sleep 86400
		certbot renew \
			--dns-cloudflare \
			--dns-cloudflare-credentials /etc/cloudflare.ini \
			--quiet >&2
	done) &
fi

# Now, make sure that the Postfix filesystem environment is sane
mkdir -p -m 0755 /var/spool/postfix/pid
chown root:root /var/spool/postfix

# Permissions are sensitive for postfix to work correctly; ensure the directory
# permissions are set as expected.
chown --recursive postfix:root /var/spool/postfix/*
[[ -d /var/spool/postfix/maildrop ]] && chown --recursive postfix:postdrop /var/spool/postfix/maildrop
[[ -d /var/spool/postfix/public ]] && chown --recursive postfix:postdrop /var/spool/postfix/public
chown --recursive root:root /var/spool/postfix/pid

/usr/sbin/postfix check >&2

echo "Starting Postfix" >&2

# Finally, let postfix-master do its thing
exec /usr/lib/postfix/sbin/master -c /etc/postfix -d
