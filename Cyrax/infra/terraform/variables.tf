variable "region"          { default = "nyc3" }
variable "droplet_size"    { default = "s-4vcpu-8gb" }
variable "domain"          { default = "example.com" }
variable "cloudflare_zone" {}
variable "cf_api_token"    { sensitive = true }
variable "do_token"        { sensitive = true }
variable "ssh_key_ids"     { type = list(string) }
variable "db_password"     { sensitive = true }
variable "node_count"      { default = 3 }
