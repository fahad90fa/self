resource "cloudflare_record" "c2_a" {
  zone_id = var.cloudflare_zone
  name    = "c2"
  value   = digitalocean_loadbalancer.lb.ip
  type    = "A"
  proxied = true
}

resource "cloudflare_zone_settings_override" "security" {
  zone_id = var.cloudflare_zone
  settings {
    security_level       = "high"
    ssl                  = "full_strict"
    min_tls_version      = "1.2"
    http3                = "on"
    always_use_https     = "on"
    opportunistic_onion  = "on"
  }
}

resource "cloudflare_ruleset" "waf" {
  zone_id = var.cloudflare_zone
  name    = "cyrax-waf"
  kind    = "zone"
  phase   = "http_request_firewall_managed"

  rules {
    action     = "execute"
    expression = "true"
    action_parameters { id = "efb7b8c949ac4650a09736fc376e9aee" }
  }
}
