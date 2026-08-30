# Installing the dashboard as an app on the family's phones

The dashboard installs as an app — its own icon on the home screen, opening full screen with no
browser around it. On an iPhone that works from a plain `http://192.168.x.y:8080` address today and
needs nothing from this directory. **Chrome does not**: it installs only from an `https://` address,
so on Android the same shortcut opens in a browser tab instead.

What follows gives the Pi a certificate that every phone already trusts, without exposing anything
to the internet and without touching a single phone. After it, an Android phone offers _Install_,
an iPhone gets offline support it did not have, and a new phone in the house needs no setup at all.

The pieces: a free name from [DuckDNS](https://www.duckdns.org) pointed at the Pi's address on your
own network, and [Caddy](https://caddyserver.com) in front of the dashboard holding a Let's Encrypt
certificate for that name. The certificate is issued over the **DNS challenge** — Let's Encrypt
checks a TXT record on the name rather than connecting to the machine — so nothing is
port-forwarded, nothing is reachable from outside, and the name resolving to `192.168.x.y` is not a
problem. Renewal is Caddy's, unattended.

Around twenty minutes, once.

## 1. Give the Pi a fixed address

In the router, reserve the Pi's current address for its MAC. The name is going to point at that
address and nothing updates it when DHCP moves the machine.

## 2. Register the name

At [duckdns.org](https://www.duckdns.org), sign in and add a domain — `solax`, giving
`solax.duckdns.org`. Copy the token from the top of the page; it is the one secret here, and it is
the same token for every name on the account.

## 3. Point it at the Pi

Fill in a copy of `caddy.env.example` on the Pi:

```bash
sudo mkdir -p /etc/caddy
sudo install -m 600 -o root -g root deploy/https/caddy.env.example /etc/caddy/caddy.env
sudo nano /etc/caddy/caddy.env
```

`SOLAX_LAN_IP` is the Pi's address from step 1, and `SOLAX_DOMAIN` the full name from step 2. The
file is only readable by root, because of the token.

The address has to be given explicitly. Asked to work it out for itself, DuckDNS records the
address the request came _from_ — the household's public one — and the name then points at the
router rather than at the Pi.

```bash
sudo install -m 644 deploy/https/duckdns-update.service deploy/https/duckdns-update.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now duckdns-update.timer
sudo systemctl start duckdns-update.service
```

The timer repeats this daily: DuckDNS drops a name that has not been updated in a month.

Check it before going on — from any machine on the network:

```bash
dig +short solax.duckdns.org        # must print the Pi's address
```

**If it prints nothing**, the router is dropping the answer. Many do by default: a public name
resolving into the local network looks like a DNS rebinding attack. Look for _DNS rebind
protection_ in the router and add `duckdns.org` to its exceptions — on a Fritz!Box it is under
Home Network → Network → Network Settings → DNS Rebind Protection. Everything below depends on this
resolving.

## 4. Install Caddy, with the DuckDNS plugin

The stock binary cannot answer a DNS challenge on DuckDNS; the plugin is compiled in. Caddy's
download service builds it:

```bash
# 64-bit Raspberry Pi OS. For a 32-bit one, arch=arm&arm=7.
curl -fsSL -o caddy "https://caddyserver.com/api/download?os=linux&arch=arm64&p=github.com/caddy-dns/duckdns"
sudo install -m 755 caddy /usr/local/bin/caddy
caddy list-modules | grep duckdns     # dns.providers.duckdns
```

A user for it to run as, and somewhere for it to keep the certificate:

```bash
sudo useradd --system --home /var/lib/caddy --create-home --shell /usr/sbin/nologin caddy
```

## 5. Put it in front of the dashboard

```bash
sudo install -m 644 -o root -g root deploy/https/Caddyfile /etc/caddy/Caddyfile
sudo install -m 644 deploy/https/solax-caddy.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now solax-caddy
journalctl -u solax-caddy -f
```

The first start writes the TXT record, waits for it to propagate and collects the certificate; give
it a minute. `certificate obtained successfully` in the log is the line to wait for.

The dashboard itself stays exactly as it was on port 8080 — Caddy proxies to it. Leave that port
open on the local network or close it; both work, and closing it means the name is the only way in.

## 6. Add it to the phones

Open `https://solax.duckdns.org` on the phone, on the home network.

- **Android:** Chrome offers _Install app_ from its menu, or the dashboard's own **Install** button
  opens the same offer. What lands on the home screen is a real app, not a shortcut.
- **iPhone:** Safari, _Share → Add to Home Screen_. It is the only browser on iOS that can.

## What this does not do

The name resolves to a private address, so it only works on the home network. From outside, the
phone gets nothing — which for a dashboard with no authentication of its own is the intended state.
If the parents should reach it from anywhere, that is a VPN back home rather than a hole in the
router: Tailscale on the Pi and on the phones does it, and `tailscale serve` provides its own
certificate, replacing all of the above.
