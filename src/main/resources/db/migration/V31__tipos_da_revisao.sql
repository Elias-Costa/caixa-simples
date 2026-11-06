-- O tipo de cada revisão, ao lado do motivo escrito, para o dispositivo saber quem pode agir sobre
-- ela: o estoque negativo é de quem ajusta estoque, e o operador não ajusta. Os nomes vão separados
-- por vírgula, na ordem do detalhe, porque uma operação pode ter mais de um tipo.
--
-- Fica gravado com o resultado porque o reenvio de uma operação devolve o que foi gravado: sem a
-- coluna, a mesma revisão chegaria ao aparelho com o tipo na primeira resposta e sem ele no reenvio.
-- As linhas de antes ficam sem tipo, e o aparelho as mostra como sempre mostrou.
ALTER TABLE operacao_sincronizada ADD COLUMN tipos_da_revisao text;

-- Só a revisão tem tipo: a aplicada sem pendência e a recusada não têm.
ALTER TABLE operacao_sincronizada ADD CONSTRAINT operacao_sincronizada_tipo_so_da_revisao
    CHECK (tipos_da_revisao IS NULL OR resultado = 'APLICADA_COM_REVISAO');
