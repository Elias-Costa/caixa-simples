-- Agregado Caixa: o estorno de uma venda cancelada (RF12).
--
-- Cancelar uma venda concluída devolve ao cliente o dinheiro que tinha entrado na gaveta, e o
-- caixa registra isso como um movimento novo, ESTORNO, e nunca apagando o movimento VENDA:
-- movimento lançado não se edita, o que se faz é lançar o oposto, para o histórico continuar
-- contando que o dinheiro entrou e depois saiu. ESTORNO subtrai do esperado o mesmo valor que a
-- VENDA daquela venda somou, aponta para a mesma venda e não pede motivo: a venda já o explica,
-- como já explicava a entrada.
--
-- Não é SANGRIA: sangria é retirada de dinheiro do caixa, e o vocabulário do domínio diz desde o
-- começo que ela não é estorno. Contar a devolução como retirada deixaria o histórico mentindo.
--
-- Os três CHECKs da V5 conheciam só VENDA, SANGRIA e SUPRIMENTO. Migration publicada é imutável,
-- então cada um é recriado aqui com o quarto tipo, sob o mesmo nome.
ALTER TABLE movimento_caixa
    DROP CONSTRAINT movimento_caixa_tipo_valido;
ALTER TABLE movimento_caixa
    ADD CONSTRAINT movimento_caixa_tipo_valido
        CHECK (tipo IN ('VENDA', 'SANGRIA', 'SUPRIMENTO', 'ESTORNO'));

-- RF14 exige motivo de sangria e de suprimento. VENDA e ESTORNO se explicam pela venda.
ALTER TABLE movimento_caixa
    DROP CONSTRAINT movimento_caixa_motivo_obrigatorio;
ALTER TABLE movimento_caixa
    ADD CONSTRAINT movimento_caixa_motivo_obrigatorio
        CHECK (tipo IN ('VENDA', 'ESTORNO') OR motivo IS NOT NULL);

-- venda_id é preenchido só nos dois tipos que vêm de uma venda.
ALTER TABLE movimento_caixa
    DROP CONSTRAINT movimento_caixa_venda_so_em_venda;
ALTER TABLE movimento_caixa
    ADD CONSTRAINT movimento_caixa_venda_so_em_venda
        CHECK (venda_id IS NULL OR tipo IN ('VENDA', 'ESTORNO'));

-- A V5 não exigiu venda_id em todo VENDA, e essa folga fica. ESTORNO é diferente: ele só existe
-- como o oposto de uma VENDA, então um estorno sem venda não tem o que espelhar.
ALTER TABLE movimento_caixa
    ADD CONSTRAINT movimento_caixa_estorno_exige_venda
        CHECK (tipo <> 'ESTORNO' OR venda_id IS NOT NULL);

-- Os dois eventos, venda concluída e venda cancelada, chegam ao caixa por um registro que entrega
-- ao menos uma vez. A raiz recusa a duplicata e o ouvinte reconhece a reentrega; este índice é a
-- rede para o caso que o código não alcança, duas entregas do mesmo evento ao mesmo tempo. Inclui
-- o tipo para que a VENDA e o ESTORNO da mesma venda caibam lado a lado, como o índice de
-- movimento_estoque já faz com a SAIDA e a ENTRADA.
CREATE UNIQUE INDEX uq_movimento_caixa_venda_por_sessao
    ON movimento_caixa (sessao_caixa_id, venda_id, tipo)
    WHERE venda_id IS NOT NULL;

-- Os comentários da V5 e da V7 descreviam três tipos; reemitidos para o banco contar a verdade
-- inteira.
COMMENT ON COLUMN movimento_caixa.tipo IS
    'VENDA é o dinheiro em espécie de uma venda concluída; ESTORNO é o mesmo dinheiro saindo quando a venda é cancelada; SANGRIA é retirada e SUPRIMENTO é reforço de troco. VENDA e SUPRIMENTO somam ao esperado, SANGRIA e ESTORNO subtraem.';
COMMENT ON COLUMN movimento_caixa.venda_id IS
    'Preenchido em VENDA e em ESTORNO, e obrigatório no segundo: o estorno espelha a venda que aponta. Referência entre agregados é por id; a FOREIGN KEY existe desde a V7.';
COMMENT ON COLUMN movimento_caixa.valor IS
    'Sempre positivo. O sinal da soma assinada vem do tipo: SANGRIA e ESTORNO subtraem, VENDA e SUPRIMENTO somam.';
COMMENT ON COLUMN movimento_caixa.motivo IS
    'RF14: obrigatório em SANGRIA e SUPRIMENTO. VENDA e ESTORNO se explicam pela venda. varchar(120) é o limite de descrição curta do schema: é texto que o operador escreve no balcão, não rótulo.';

-- O estorno do cancelamento também devolve o estoque: cada SAIDA que a venda gerou ganha uma
-- ENTRADA da mesma venda, sob o índice que a V9 já preparou. Os comentários da V9 e da V10 diziam
-- que ENTRADA esperava o estorno; agora ele existe.
COMMENT ON COLUMN movimento_estoque.venda_id IS
    'Preenchido quando o movimento vem de uma venda: a SAIDA da conclusão e a ENTRADA do cancelamento, que devolve a mesma quantidade. Referência entre agregados é por id; a FOREIGN KEY protege o dado sem criar objeto navegável.';
COMMENT ON COLUMN movimento_estoque.tipo IS
    'SAIDA é a baixa da venda concluída e subtrai; ENTRADA é o estorno da venda cancelada e soma. AJUSTE é o acerto manual do RF19, e nele o sinal está na quantidade.';
