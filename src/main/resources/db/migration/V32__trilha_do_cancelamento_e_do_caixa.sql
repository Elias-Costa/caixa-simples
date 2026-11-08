-- Quem cancelou a venda e quando, e quem lançou cada movimento de caixa.
--
-- O operador cancela a própria venda concluída, e o dinheiro sai da gaveta como estorno. Sem a
-- trilha, o dono via a venda cancelada sem saber quem a cancelou nem quando, e a sangria lançada no
-- caixa de um operador não dizia quem a lançou. A trilha fica no banco, ao lado do caixa que o dono
-- já confere, por quanto tempo a Conta existir.
--
-- As linhas de antes ficam nulas: não há como saber quem fez o que já estava gravado.

-- O instante e o usuário de quem cancelou. Valem para a comanda abandonada e para a venda
-- concluída que o cliente devolveu, porque as duas terminam em CANCELADA.
ALTER TABLE venda ADD COLUMN cancelada_em timestamptz;
ALTER TABLE venda ADD COLUMN cancelada_por uuid REFERENCES usuario (id);

-- Os dois nascem juntos: um cancelamento com instante e sem autor, ou o contrário, é meia trilha.
ALTER TABLE venda ADD CONSTRAINT venda_cancelamento_inteiro
    CHECK ((cancelada_em IS NULL) = (cancelada_por IS NULL));

-- Só a venda cancelada tem quem a cancelou.
ALTER TABLE venda ADD CONSTRAINT venda_cancelamento_so_da_cancelada
    CHECK (cancelada_em IS NULL OR status = 'CANCELADA');

-- O usuário que lançou o movimento: quem concluiu a venda, quem a cancelou, quem lançou a sangria
-- ou o suprimento, quem recebeu o fiado. Pode não ser o dono do caixa, porque o administrador
-- lança e cancela em qualquer caixa da Conta.
ALTER TABLE movimento_caixa ADD COLUMN usuario_id uuid REFERENCES usuario (id);

COMMENT ON COLUMN venda.cancelada_por IS
    'Quem cancelou a venda. Nulo na venda não cancelada e na cancelada antes de a trilha existir.';
COMMENT ON COLUMN movimento_caixa.usuario_id IS
    'Quem lançou o movimento. Nulo só nas linhas gravadas antes de a trilha existir.';
