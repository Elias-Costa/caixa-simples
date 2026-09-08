-- Etapa 1.4 do plano de implementacao (passo R07 do roteiro): regra de abertura do caixa.
--
-- Decisao D22a: uma sessao ABERTA por operador. Dois atendentes podem ter caixas simultaneos no
-- mesmo negocio (escopo §5 fala do PROPRIO caixa de cada um); o mesmo operador com dois, nao.
--
-- Migration nova, e nao um ALTER na V5: a V5 fechou dizendo que, se a regra virasse decisao no R07,
-- entraria em versao nova. Migration commitada e imutavel (regra 5 do CLAUDE.md).

-- Parcial de proposito: sessao FECHADA nao ocupa o lugar, senao o operador abriria caixa uma vez
-- na vida. Mesmo padrao do idx_produto_codigo da V2, que so vale enquanto o produto esta ativo.
--
-- Este indice e a rede embaixo da checagem de SessaoCaixaService.abrir, nao a mensagem de erro do
-- dia a dia: ele existe para o caso de duas requisicoes passarem juntas pela checagem previa.
CREATE UNIQUE INDEX idx_sessao_caixa_aberta_por_operador
    ON sessao_caixa (conta_id, usuario_id)
    WHERE status = 'ABERTA';

COMMENT ON INDEX idx_sessao_caixa_aberta_por_operador IS
    'D22a — uma sessao ABERTA por operador dentro da conta. Parcial: sessao FECHADA nao ocupa o lugar.';
