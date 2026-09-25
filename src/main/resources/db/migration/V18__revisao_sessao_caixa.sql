-- Cada movimento e o fechamento mudam a revisão da raiz para que o dispositivo
-- conserve a versão lida antes de operar sem rede.
ALTER TABLE sessao_caixa ADD COLUMN versao bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN sessao_caixa.versao IS 'Revisão da raiz, inclusive movimentos, usada na reconciliação offline.';
