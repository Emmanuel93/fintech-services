-- liquibase formatted sql

-- changeset identity:011 author:fintech
-- Qué puede hacer cada rol.
--
-- Vivía escrita en el código del BFF (`PermissionsService`), y ahí cambiarla exigía un
-- despliegue: para mover una capacidad de un rol a otro había que compilar, publicar imagen y
-- reiniciar el canal. Eso no es una política de acceso, es una constante.
--
-- Vive en identity porque identity es quien ya responde "qué roles tiene esta persona"; la
-- capacidad es la otra mitad de la misma pregunta. Se guarda por canal —hoy sólo BACKOFFICE—
-- porque `applications.decide` no significa nada para la app móvil y no debe mezclarse.
--
-- `system_managed` marca los roles que el producto define y no se borran desde la consola: se
-- pueden reasignar capacidades, pero no dejar la instalación sin ADMIN.
CREATE TABLE identity.roles (
    code            VARCHAR(50)   NOT NULL,
    name            VARCHAR(120)  NOT NULL,
    description     TEXT,
    channel         VARCHAR(30)   NOT NULL DEFAULT 'BACKOFFICE',
    system_managed  BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_roles PRIMARY KEY (code)
);

CREATE TABLE identity.role_capabilities (
    role_code   VARCHAR(50)  NOT NULL,
    capability  VARCHAR(80)  NOT NULL,
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    granted_by  VARCHAR(120),
    CONSTRAINT pk_role_capabilities PRIMARY KEY (role_code, capability),
    CONSTRAINT fk_role_capabilities_role FOREIGN KEY (role_code)
        REFERENCES identity.roles (code) ON DELETE CASCADE
);

CREATE INDEX role_capabilities_capability_idx ON identity.role_capabilities (capability);

-- El catálogo de roles: los mismos trece del enum StaffRole, con la descripción que la consola
-- ya mostraba escrita a mano.
INSERT INTO identity.roles (code, name, description) VALUES
    ('ADMIN',             'Administrador',        'Acceso total, incluida la administración de personal. Es el rol de operación de la plataforma, no un rol de negocio.'),
    ('OPS_SUPERVISOR',    'Supervisor de operación', 'Supervisa la operación diaria y la cartera de su equipo.'),
    ('CREDIT_ANALYST',    'Analista de crédito',  'Analiza el expediente —score, reglas, buró— y resuelve lo que el motor mandó a revisión.'),
    ('UNDERWRITER',       'Underwriter',          'Decide sobre solicitudes en revisión manual.'),
    ('COMMITTEE',         'Comité de crédito',    'Decide lo que por monto o riesgo no puede resolver una sola persona.'),
    ('EXECUTIVE',         'Ejecutivo de cartera', 'Atiende a los clientes que tiene asignados. El alcance lo aplica el backend, no la pantalla.'),
    -- El puesto que dirige una rama comercial. Faltaba, y se notaba en una asimetría: la
    -- estructura sabía acotar lo que **se ve** —un gerente de zona ve su zona y lo que cuelga—
    -- pero nadie por debajo de los roles transversales podía **cambiarla**. `salesorg.manage`
    -- sólo la tenían ADMIN y OPS_SUPERVISOR, que ven la red entera, así que reorganizar era todo
    -- o nada: o movías el país o no movías nada.
    --
    -- Es **uno** y no cuatro (sucursal, zona, región, nacional): el alcance sale de la unidad a
    -- la que la persona está adscrita, no del rol. Cuatro roles obligarían a repetir cada regla
    -- cuatro veces y a rehacerlas el día que se abra una subdirección entre región y zona. Con
    -- uno, el nacional no es un caso especial: es quien está adscrito a la raíz.
    --
    -- Deliberadamente **fuera** de los roles transversales del BFF (ADMIN, OPS_SUPERVISOR,
    -- RISK_ANALYST, FINANCE, AUDITOR): ésos ven cualquier unidad por diseño, y si éste entrara en
    -- esa lista su alcance dejaría de venir de su nodo y el rol no serviría para nada.
    ('COMMERCIAL_MANAGER', 'Gerente comercial',
     'Dirige una rama de la estructura comercial. Ve y reorganiza su unidad y todo lo que cuelga de ella —y nada fuera de ahí—. El nivel no lo da el rol sino el nodo al que está adscrito.'),
    ('COLLECTIONS_AGENT', 'Gestor de cobranza',   'Atiende casos en mora.'),
    ('RISK_ANALYST',      'Analista de riesgo',   'Analiza riesgo de cartera, etapas IFRS-9 y provisiones.'),
    ('PRODUCT_MANAGER',   'Administrador de producto', 'Publica versiones de producto; lo que active es lo que ve el cliente en la app.'),
    ('FINANCE',           'Finanzas',             'Comisiones, liquidaciones, contabilidad y facturación.'),
    ('MARKETING',         'Marketing',            'Campañas y políticas de notificación.'),
    ('AUDITOR',           'Auditor',              'Sólo lectura de la bitácora y los expedientes. No toca cartera ni decisiones.'),
    ('SUPPORT',           'Soporte',              'Consulta de clientes para atención, sin facultades de decisión.');

-- La matriz tal como la aplicaba el BFF. Se siembra idéntica a propósito: al cambiar de fuente
-- nadie debe ganar ni perder un permiso el día del despliegue.
--
-- Las dos excepciones son `permissions.view` y `permissions.manage`: la pantalla de roles se
-- protegía con una lista de roles escrita en el controlador en vez de con una capacidad, así que
-- la navegación —que sí se deriva de la matriz— la mostraba a todo el mundo y el backend la
-- rechazaba al abrirla. Se les da exactamente a quienes ya la tenían por esa lista: ver a ADMIN y
-- OPS_SUPERVISOR, editar sólo a ADMIN.
INSERT INTO identity.role_capabilities (role_code, capability, granted_by) VALUES
    ('ADMIN','applications.analyze','seed'),
    ('ADMIN','applications.decide','seed'),
    ('ADMIN','applications.request-documents','seed'),
    ('ADMIN','applications.view','seed'),
    ('ADMIN','audit.view','seed'),
    ('ADMIN','clients.assign-executive','seed'),
    ('ADMIN','clients.view','seed'),
    ('ADMIN','dashboard.commercial','seed'),
    ('ADMIN','dashboard.view','seed'),
    ('ADMIN','permissions.view','seed'),
    ('ADMIN','permissions.manage','seed'),
    ('ADMIN','portfolio.view','seed'),
    ('ADMIN','products.manage','seed'),
    ('ADMIN','salesorg.manage','seed'),
    ('ADMIN','salesorg.view','seed'),
    ('AUDITOR','applications.analyze','seed'),
    ('AUDITOR','applications.view','seed'),
    ('AUDITOR','audit.view','seed'),
    ('AUDITOR','clients.view','seed'),
    ('AUDITOR','dashboard.commercial','seed'),
    ('AUDITOR','dashboard.view','seed'),
    ('AUDITOR','portfolio.view','seed'),
    ('AUDITOR','salesorg.view','seed'),
    ('COLLECTIONS_AGENT','portfolio.view','seed'),
    ('COMMITTEE','applications.analyze','seed'),
    ('COMMITTEE','applications.decide','seed'),
    ('COMMITTEE','applications.request-documents','seed'),
    ('COMMITTEE','applications.view','seed'),
    ('COMMITTEE','clients.view','seed'),
    ('COMMITTEE','dashboard.view','seed'),
    ('COMMITTEE','portfolio.view','seed'),
    ('CREDIT_ANALYST','applications.analyze','seed'),
    ('CREDIT_ANALYST','applications.request-documents','seed'),
    ('CREDIT_ANALYST','applications.view','seed'),
    ('CREDIT_ANALYST','clients.view','seed'),
    ('CREDIT_ANALYST','dashboard.view','seed'),
    ('CREDIT_ANALYST','portfolio.view','seed'),
    ('EXECUTIVE','clients.view','seed'),
    ('EXECUTIVE','dashboard.commercial','seed'),
    ('EXECUTIVE','dashboard.view','seed'),
    ('EXECUTIVE','portfolio.view','seed'),
    ('EXECUTIVE','salesorg.view','seed'),
    -- Lo que dirigir una rama exige: verla, reorganizarla y mover su cartera.
    --
    -- Lleva `clients.assign-executive` porque reasignar cartera entre su gente es la mitad del
    -- puesto; sin eso podría mover a las personas de unidad pero no a los clientes de persona,
    -- que es la operación que de verdad se hace todos los días.
    --
    -- No lleva `applications.decide` ni `permissions.manage`: dirigir una red comercial no es
    -- decidir crédito ni repartir permisos, y juntarlos lo convertiría en otro administrador.
    ('COMMERCIAL_MANAGER','salesorg.view','seed'),
    ('COMMERCIAL_MANAGER','salesorg.manage','seed'),
    ('COMMERCIAL_MANAGER','dashboard.view','seed'),
    ('COMMERCIAL_MANAGER','dashboard.commercial','seed'),
    ('COMMERCIAL_MANAGER','clients.view','seed'),
    ('COMMERCIAL_MANAGER','clients.assign-executive','seed'),
    ('COMMERCIAL_MANAGER','portfolio.view','seed'),
    ('FINANCE','dashboard.commercial','seed'),
    ('FINANCE','dashboard.view','seed'),
    ('FINANCE','portfolio.view','seed'),
    ('FINANCE','salesorg.view','seed'),
    ('OPS_SUPERVISOR','applications.view','seed'),
    ('OPS_SUPERVISOR','clients.assign-executive','seed'),
    ('OPS_SUPERVISOR','clients.view','seed'),
    ('OPS_SUPERVISOR','dashboard.commercial','seed'),
    ('OPS_SUPERVISOR','dashboard.view','seed'),
    ('OPS_SUPERVISOR','permissions.view','seed'),
    ('OPS_SUPERVISOR','portfolio.view','seed'),
    ('OPS_SUPERVISOR','salesorg.manage','seed'),
    ('OPS_SUPERVISOR','salesorg.view','seed'),
    ('PRODUCT_MANAGER','dashboard.view','seed'),
    ('PRODUCT_MANAGER','products.manage','seed'),
    ('RISK_ANALYST','applications.analyze','seed'),
    ('RISK_ANALYST','applications.view','seed'),
    ('RISK_ANALYST','clients.view','seed'),
    ('RISK_ANALYST','dashboard.commercial','seed'),
    ('RISK_ANALYST','dashboard.view','seed'),
    ('RISK_ANALYST','portfolio.view','seed'),
    ('RISK_ANALYST','salesorg.view','seed'),
    ('SUPPORT','clients.view','seed'),
    ('SUPPORT','dashboard.view','seed'),
    ('SUPPORT','portfolio.view','seed'),
    ('UNDERWRITER','applications.analyze','seed'),
    ('UNDERWRITER','applications.decide','seed'),
    ('UNDERWRITER','applications.request-documents','seed'),
    ('UNDERWRITER','applications.view','seed'),
    ('UNDERWRITER','clients.view','seed'),
    ('UNDERWRITER','dashboard.view','seed'),
    ('UNDERWRITER','portfolio.view','seed');
