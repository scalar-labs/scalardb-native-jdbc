# The benchmark environment of docs/plans/benchmark-environment.md: a client, the tier A databases
# on EC2, the tier B databases on RDS. Every run ends in `terraform destroy`.

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "admin_cidr" {
  description = "The addresses SSH to the client is allowed from, CIDR blocks separated by commas"
  type        = string
}

variable "ssh_public_key" {
  description = "The public key put on the client"
  type        = string
}

variable "db_password" {
  description = "The password of every database's admin user"
  type        = string
  sensitive   = true
}

variable "tier_a" {
  description = "Engines on EC2: postgresql, oracle, mysql, mariadb, sqlserver"
  type        = set(string)
  default     = ["postgresql", "oracle"]
}

variable "tier_b" {
  description = "Engines on RDS: postgresql, oracle, mysql, mariadb"
  type        = set(string)
  default     = []
}

variable "oracle_image" {
  description = "Oracle 23ai Enterprise Edition image (container-registry.oracle.com needs the user's login); empty runs Oracle Free"
  type        = string
  default     = ""
}

variable "oracle_registry_user" {
  type      = string
  default   = ""
  sensitive = true
}

variable "oracle_registry_token" {
  type      = string
  default   = ""
  sensitive = true
}

variable "client_profile" {
  description = "The client's instance profile (setup-account.sh), for runs without the laptop; empty for none"
  type        = string
  default     = ""
}

variable "db_memory_mb" {
  description = "Tier A: memory per database container, file cache included, with the buffer pool to fit; 0 for none"
  type        = number
  default     = 0
}

variable "pg_stat_statements" {
  description = "Tier A PostgreSQL: pg_stat_statements loaded, planning tracked, for BenchBase's server statistics (matrix.sh BENCHBASE=1)"
  type        = bool
  default     = false
}

variable "profiling" {
  description = "Tier A: perf installed on the database hosts, for perf-tier-a.sh to profile the engine during a run"
  type        = bool
  default     = false
}

variable "lifetime_minutes" {
  description = "Each EC2 instance terminates itself this long after boot"
  type        = number
  default     = 600
}

# Credentials from the environment, which run.sh exports from `aws login --profile scalardb-bench`
provider "aws" {
  region = var.region
  default_tags {
    tags = { project = "scalardb-native-jdbc-bench" }
  }
}

data "aws_availability_zones" "up" {
  state = "available"
}

data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical
  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }
}

resource "aws_vpc" "bench" {
  cidr_block           = "10.42.0.0/16"
  enable_dns_hostnames = true
}

resource "aws_internet_gateway" "bench" {
  vpc_id = aws_vpc.bench.id
}

# Everything runs in the first subnet; RDS wants a second availability zone in its subnet group.
resource "aws_subnet" "bench" {
  count                   = 2
  vpc_id                  = aws_vpc.bench.id
  cidr_block              = "10.42.${count.index}.0/24"
  availability_zone       = data.aws_availability_zones.up.names[count.index]
  map_public_ip_on_launch = true
}

resource "aws_route_table" "bench" {
  vpc_id = aws_vpc.bench.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.bench.id
  }
}

resource "aws_route_table_association" "bench" {
  count          = 2
  subnet_id      = aws_subnet.bench[count.index].id
  route_table_id = aws_route_table.bench.id
}

resource "aws_security_group" "client" {
  vpc_id = aws_vpc.bench.id
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = split(",", var.admin_cidr)
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "db" {
  vpc_id = aws_vpc.bench.id
  ingress {
    from_port       = 0
    to_port         = 65535
    protocol        = "tcp"
    security_groups = [aws_security_group.client.id]
  }
  egress { # image pulls on tier A
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_key_pair" "bench" {
  key_name_prefix = "bench-"
  public_key      = var.ssh_public_key
}

locals {
  subnet = aws_subnet.bench[0].id
  rds = {
    postgresql = { engine = "postgres", version = "16", license = null }
    oracle     = { engine = "oracle-se2", version = "19", license = "license-included" }
    mysql      = { engine = "mysql", version = "8.4", license = null }
    mariadb    = { engine = "mariadb", version = "11.4", license = null }
  }
}

resource "aws_instance" "client" {
  ami                                  = data.aws_ami.ubuntu.id
  instance_type                        = "m6i.4xlarge" # both tiers' JVMs side by side
  subnet_id                            = local.subnet
  vpc_security_group_ids               = [aws_security_group.client.id]
  key_name                             = aws_key_pair.bench.key_name
  iam_instance_profile                 = var.client_profile == "" ? null : var.client_profile
  instance_initiated_shutdown_behavior = "terminate"
  metadata_options {
    http_tokens = "required" # IMDSv2 only
  }
  user_data = templatefile("${path.module}/client.sh.tftpl", { lifetime = var.lifetime_minutes })
  root_block_device {
    volume_size = 50
    volume_type = "gp3"
  }
  tags = { Name = "bench-client" }
}

resource "aws_instance" "db" {
  for_each                             = toset(var.tier_a)
  ami                                  = data.aws_ami.ubuntu.id
  instance_type                        = "m6id.2xlarge"
  subnet_id                            = local.subnet
  vpc_security_group_ids               = [aws_security_group.db.id]
  key_name                             = aws_key_pair.bench.key_name
  instance_initiated_shutdown_behavior = "terminate"
  metadata_options {
    http_tokens = "required" # IMDSv2 only
  }
  user_data = templatefile("${path.module}/db-a.sh.tftpl", {
    engine         = each.key
    password       = var.db_password
    lifetime       = var.lifetime_minutes
    db_memory_mb   = var.db_memory_mb
    pgss           = var.pg_stat_statements
    profiling      = var.profiling
    oracle_image   = var.oracle_image
    registry_user  = var.oracle_registry_user
    registry_token = var.oracle_registry_token
  })
  root_block_device {
    volume_size = 30
    volume_type = "gp3"
  }
  tags = { Name = "bench-a-${each.key}" }
}

resource "aws_db_subnet_group" "bench" {
  count       = length(var.tier_b) > 0 ? 1 : 0
  name_prefix = "bench-" # the benchmark role may tag only RDS resources named bench-*
  subnet_ids  = aws_subnet.bench[*].id
}

resource "aws_db_instance" "db" {
  for_each                = var.tier_b
  identifier_prefix       = "bench-b-${each.key}-"
  engine                  = local.rds[each.key].engine
  engine_version          = local.rds[each.key].version
  license_model           = local.rds[each.key].license
  instance_class          = "db.m5.2xlarge" # Oracle SE2 licence included exists on no m6i class
  allocated_storage       = 100
  storage_type            = "gp3"
  db_name                 = each.key == "oracle" ? "BENCH" : (each.key == "postgresql" ? "bench" : null)
  username                = "bench"
  password                = var.db_password
  availability_zone       = aws_subnet.bench[0].availability_zone
  db_subnet_group_name    = aws_db_subnet_group.bench[0].name
  vpc_security_group_ids  = [aws_security_group.db.id]
  backup_retention_period = 0
  skip_final_snapshot     = true
  apply_immediately       = true
}

output "client" {
  value = aws_instance.client.public_ip
}

# JDBC URLs as the client reaches them, for -Dnativejdbc.<engine>.url
output "urls" {
  value = merge(
    { for e, i in aws_instance.db : "a-${e}" => {
      postgresql = "jdbc:postgresql://${i.private_ip}:5432/postgres"
      oracle     = "jdbc:oracle:thin:@${i.private_ip}:1521/${var.oracle_image == "" ? "FREEPDB1" : "ORCLPDB1"}"
      mysql      = "jdbc:mysql://${i.private_ip}:3306/?allowPublicKeyRetrieval=true"
      mariadb    = "jdbc:mariadb://${i.private_ip}:3306/"
      sqlserver  = "jdbc:sqlserver://${i.private_ip}:1433;encrypt=false;databaseName=appdb"
    }[e] },
    { for e, d in aws_db_instance.db : "b-${e}" => {
      postgresql = "jdbc:postgresql://${d.address}:5432/bench"
      oracle     = "jdbc:oracle:thin:@${d.address}:1521/BENCH"
      mysql      = "jdbc:mysql://${d.address}:3306/?allowPublicKeyRetrieval=true"
      mariadb    = "jdbc:mariadb://${d.address}:3306/"
    }[e] }
  )
}
