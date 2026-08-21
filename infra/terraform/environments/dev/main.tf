terraform {
  required_version = ">= 1.7"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
  # Descomentar y configurar cuando se implemente
  # backend "s3" {
  #   bucket = "fintech-terraform-state-dev"
  #   key    = "dev/terraform.tfstate"
  #   region = var.aws_region
  # }
}

provider "aws" {
  region = var.aws_region
}

# Placeholder — módulos se configuran en la implementación de producción
