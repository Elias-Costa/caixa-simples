-- Regra de abertura do caixa: uma sessão ABERTA por operador. Dois atendentes podem ter caixas
-- simultâneos no mesmo negócio, já que cada um responde pelo próprio caixa; o mesmo operador com
-- dois, não.
--
-- Migration nova, e não um ALTER na V5, porque migration commitada é imutável.

-- Parcial de propósito: sessão FECHADA não ocupa o lugar, senão o operador abriria caixa uma vez
-- na vida. Mesmo padrão do idx_produto_codigo da V2, que só vale enquanto o produto está ativo.
--
-- Este índice é a rede embaixo da checagem de SessaoCaixaService.abrir, não a mensagem de erro do
-- dia a dia: ele existe para o caso de duas requisições passarem juntas pela checagem prévia.
CREATE UNIQUE INDEX idx_sessao_caixa_aberta_por_operador
    ON sessao_caixa (conta_id, usuario_id)
    WHERE status = 'ABERTA';

COMMENT ON INDEX idx_sessao_caixa_aberta_por_operador IS
    'Uma sessão ABERTA por operador dentro da conta. Parcial: sessão FECHADA não ocupa o lugar.';
