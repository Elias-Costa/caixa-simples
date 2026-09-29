-- A anonimização preserva a linha referenciada pelas Vendas, mas precisa ser distinta da
-- inativação comum para impedir edição e reativação posteriores (RNF07).
ALTER TABLE cliente ADD COLUMN removido_em timestamptz;

COMMENT ON COLUMN cliente.removido_em IS
    'Instante da anonimização a pedido. Nulo para o Cliente apenas ativo ou inativado.';
