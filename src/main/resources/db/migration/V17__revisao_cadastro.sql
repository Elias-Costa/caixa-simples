-- A revisão acompanha Produto e Cliente para que uma alteração feita sem rede conserve
-- a versão lida no balcão. O servidor poderá registrar divergências sem confiar no relógio local.
ALTER TABLE produto ADD COLUMN versao bigint NOT NULL DEFAULT 0;
ALTER TABLE cliente ADD COLUMN versao bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN produto.versao IS 'Revisão da raiz, inclusive movimentos de estoque, usada na reconciliação offline.';
COMMENT ON COLUMN cliente.versao IS 'Revisão do cadastro usada na reconciliação offline.';
