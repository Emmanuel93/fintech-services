# infra/ — Infraestructura como Código (IaC)

Estructura Terraform para el despliegue de fintech-services en AWS.
**Estado: placeholder** — los archivos `.tf` se completan cuando el sistema sea llevado a producción.

## Estructura

```
infra/
└── terraform/
    ├── environments/
    │   ├── dev/          ← Entorno de desarrollo (bajo costo, sin HA)
    │   ├── staging/      ← Pre-producción (paridad con prod)
    │   └── prod/         ← Producción (HA, Multi-AZ)
    └── modules/
        ├── database/     ← RDS PostgreSQL 16 Multi-AZ
        ├── kafka/        ← MSK (Amazon Managed Streaming for Kafka)
        ├── app/          ← ECS Fargate o EC2 con Auto Scaling
        └── networking/   ← VPC, Subnets, Security Groups, ALB
```

## Prerequisitos

- Terraform >= 1.7
- AWS CLI configurado con credenciales de la cuenta destino
- Backend S3 para tfstate (configurar antes del primer `terraform init`)

## Uso

```bash
cd infra/terraform/environments/dev
cp terraform.tfvars.example terraform.tfvars
# Editar terraform.tfvars con valores reales

terraform init
terraform plan
terraform apply
```
