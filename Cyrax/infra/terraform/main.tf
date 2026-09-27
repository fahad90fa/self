terraform {
  required_providers {
    digitalocean = { source = "digitalocean/digitalocean" version = "~> 2.0" }
    cloudflare   = { source = "cloudflare/cloudflare" version = "~> 4.0" }
  }
}

provider "digitalocean" { token = var.do_token }
provider "cloudflare"   { api_token = var.cf_api_token }

resource "digitalocean_droplet" "c2" {
  count    = var.node_count
  name     = "c2-node-${count.index}"
  region   = var.region
  size     = var.droplet_size
  image    = "ubuntu-22-04-x64"
  ssh_keys = var.ssh_key_ids
  tags     = ["c2", "backend"]

  user_data = <<-EOF
    #!/bin/bash
    apt-get update -qq
    apt-get install -y docker.io docker-compose ufw certbot
    ufw allow 22/tcp
    ufw allow 443/tcp
    ufw allow 80/tcp
    ufw --force enable
  EOF
}

resource "digitalocean_database_cluster" "pg" {
  name       = "cyrax-pg"
  engine     = "pg"
  version    = "15"
  size       = "db-s-2vcpu-4gb"
  region     = var.region
  node_count = 1
}

resource "digitalocean_loadbalancer" "lb" {
  name   = "cyrax-lb"
  region = var.region

  forwarding_rule {
    entry_protocol  = "https"
    entry_port      = 443
    target_protocol = "http"
    target_port     = 8080
    tls_passthrough = false
  }

  healthcheck {
    protocol = "http"
    port     = 8080
    path     = "/health"
  }

  droplet_ids = digitalocean_droplet.c2[*].id
}

output "node_ips"  { value = digitalocean_droplet.c2[*].ipv4_address }
output "lb_ip"     { value = digitalocean_loadbalancer.lb.ip }
output "db_host"   { value = digitalocean_database_cluster.pg.host }
