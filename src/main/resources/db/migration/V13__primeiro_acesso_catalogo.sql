-- A marca distingue o primeiro acesso de uma Conta de um catálogo vazio ou já editado (RF32).
ALTER TABLE conta
    ADD COLUMN catalogo_inicial_aplicado boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN conta.catalogo_inicial_aplicado IS
    'RF32: o primeiro login de ADMIN já aplicou o catálogo sugerido desta Conta.';
