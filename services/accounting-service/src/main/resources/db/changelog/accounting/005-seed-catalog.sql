--liquibase formatted sql
--changeset accounting:005-seed-catalog author:system
--comment Catálogo mínimo de cuentas + reglas de posteo. Ajustable con el catálogo CNBV real antes de producción.

INSERT INTO accounting.ledger_accounts (code, name, type) VALUES
    ('1101', 'Bancos / SPEI',                                 'ASSET'),
    ('1201', 'Cartera de crédito vigente',                    'ASSET'),
    ('1203', 'Intereses y comisiones por cobrar',             'ASSET'),
    ('1210', 'Cartera de crédito castigada',                  'ASSET'),
    ('1290', 'Estimación preventiva para riesgos crediticios','ASSET'),
    ('2101', 'Fondos de clientes por disponer',               'LIABILITY'),
    ('2110', 'IVA trasladado por pagar',                      'LIABILITY'),
    ('4101', 'Ingresos por intereses',                        'INCOME'),
    ('4102', 'Ingresos por intereses moratorios',             'INCOME'),
    ('4103', 'Ingresos por comisiones',                       'INCOME'),
    ('4104', 'Recuperación de cartera castigada',             'INCOME'),
    ('5101', 'Gasto por estimación preventiva',               'EXPENSE'),
    ('5102', 'Gasto por quebranto / quita',                   'EXPENSE'),
    ('5103', 'Gasto por condonación',                         'EXPENSE'),
    ('2120', 'Comisiones por pagar',                          'LIABILITY'),
    ('5104', 'Gasto por comisiones',                          'EXPENSE');

INSERT INTO accounting.posting_rules (trigger_event, debit_account, credit_account, description) VALUES
    ('CHARGE_ORDINARY_INTEREST',   '1203', '4101', 'Interés ordinario devengado'),
    ('CHARGE_MORATORIUM_INTEREST', '1203', '4102', 'Interés moratorio devengado'),
    ('CHARGE_OPENING_FEE',         '1203', '4103', 'Comisión de apertura'),
    ('CHARGE_ADMIN_FEE',           '1203', '4103', 'Comisión de administración'),
    ('CHARGE_PREPAYMENT_FEE',      '1203', '4103', 'Comisión por prepago'),
    ('CHARGE_INSURANCE_PREMIUM',   '1203', '4103', 'Prima de seguro'),
    ('CHARGE_IVA',                 '1203', '2110', 'IVA trasladado'),
    ('CHARGE_REVERSED',            '4103', '1203', 'Reversa de cargo — cancela ingreso'),
    ('CHARGE_WAIVED',              '5103', '1203', 'Condonación — gasto P&L'),
    ('PAYMENT_APPLIED',            '1101', '1201', 'Pago recibido'),
    ('PAYMENT_RETURNED',           '1201', '1101', 'Devolución de pago'),
    ('DISPOSITION_SELF_USE',       '1201', '2101', 'Disposición a wallet (queda en la plataforma)'),
    ('DISPOSITION_THIRD_PARTY_CREDIT', '1201', '1101', 'Disposición a tercero (SPEI)'),
    ('DISPOSITION_PAYROLL',        '1201', '1101', 'Disposición nómina (SPEI)'),
    ('WALLET_WITHDRAWAL',          '2101', '1101', 'Retiro de wallet — liquida fondos de clientes'),
    ('RECOVERY_PAYMENT',           '1101', '4104', 'Recuperación post-quebranto');
