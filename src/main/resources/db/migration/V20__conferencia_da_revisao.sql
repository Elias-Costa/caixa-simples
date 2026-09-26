-- A conferência de uma revisão ou recusa pelo administrador da Conta. É a única escrita depois da
-- gravação: o gesto, o resultado e o detalhe continuam como o servidor os gravou, e conferir não
-- desfaz nem refaz efeito nenhum.
ALTER TABLE operacao_sincronizada ADD COLUMN conferida_em timestamptz;
ALTER TABLE operacao_sincronizada ADD COLUMN conferida_por uuid REFERENCES usuario (id);

-- Quem e quando andam juntos: um sem o outro seria uma conferência pela metade.
ALTER TABLE operacao_sincronizada ADD CONSTRAINT operacao_sincronizada_conferencia_completa
    CHECK ((conferida_em IS NULL) = (conferida_por IS NULL));

-- Só revisão e recusa têm o que conferir; a operação aplicada sem pendência nunca é conferida.
ALTER TABLE operacao_sincronizada ADD CONSTRAINT operacao_sincronizada_conferencia_da_pendencia
    CHECK (conferida_em IS NULL OR resultado <> 'APLICADA');

-- A lista do administrador lê as pendências ainda não conferidas e as conferidas num dia. Os dois
-- índices são parciais porque quase toda operação é aplicada sem pendência e nunca entra neles.
CREATE INDEX idx_operacao_sincronizada_pendencias
    ON operacao_sincronizada (conta_id, recebida_em)
    WHERE resultado <> 'APLICADA' AND conferida_em IS NULL;
CREATE INDEX idx_operacao_sincronizada_conferidas
    ON operacao_sincronizada (conta_id, conferida_em)
    WHERE conferida_em IS NOT NULL;

COMMENT ON COLUMN operacao_sincronizada.conferida_em IS
    'Quando o administrador conferiu a revisão ou a recusa; nulo enquanto ninguém conferiu.';
COMMENT ON COLUMN operacao_sincronizada.conferida_por IS
    'O administrador que conferiu. A primeira conferência vale, e repetir não a troca.';
