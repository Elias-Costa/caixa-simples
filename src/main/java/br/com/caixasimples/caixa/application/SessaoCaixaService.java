package br.com.caixasimples.caixa.application;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.caixa.internal.LinhaDoHistorico;
import br.com.caixasimples.caixa.internal.SessaoCaixaEntity;
import br.com.caixasimples.caixa.internal.SessaoCaixaRepository;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso da sessão de caixa: abrir (RF13), registrar sangria e suprimento (RF14), fechar com
 * conferência (RF15) e consultar o histórico por operador e por dia (RF16).
 *
 * <p>Cada caso de uso de escrita é sempre a mesma sequência: carrega a linha, deixa a raiz do
 * agregado decidir, grava o que ela decidiu. Nenhuma regra de dinheiro mora aqui. É
 * {@link SessaoCaixa} que sabe que sangria exige motivo (RF14), que não se retira mais do que há na
 * gaveta, que sessão fechada não aceita movimento e que ela tampouco fecha de novo.
 *
 * <p><strong>Duas regras moram neste arquivo, e as duas por não caberem na raiz.</strong> A
 * primeira é a de uma sessão aberta por operador, porque uma sessão não enxerga as outras sessões
 * da conta, e quem pergunta se o operador já tem caixa aberto tem de ser quem fala com o
 * repositório. A segunda é a delimitação do dia, porque traduzir uma data de calendário para um
 * intervalo de instantes é trabalho de quem monta a consulta, não de um agregado que só conhece o
 * próprio expediente.
 *
 * <p><strong>{@link #fechar} não pergunta quem está fechando.</strong> O {@code @TenantId} já
 * garante que a sessão é da própria conta (RNF05), e a autorização por perfil ainda não existe no
 * sistema. Até que exista, nada impede um operador de fechar o caixa do colega. É custo aceito, e
 * está escrito aqui para não passar por esquecimento.
 *
 * <p>O {@code usuarioId} chega como parâmetro porque ainda não há camada {@code web/} neste módulo.
 * Quando ela nascer, o valor virá do claim do token autenticado e nunca do payload (RNF05), que é o
 * mesmo que já vale para o {@code contaId}, o qual não aparece em assinatura nenhuma deste arquivo.
 */
@Service
public class SessaoCaixaService {

    private final SessaoCaixaRepository sessoes;

    SessaoCaixaService(SessaoCaixaRepository sessoes) {
        this.sessoes = sessoes;
    }

    /**
     * Abertura de caixa (RF13): o operador informa o que há na gaveta e a sessão nasce ABERTA, com
     * o esperado já igual a esse valor.
     *
     * <p><strong>Consulta antes de gravar</strong>, ao contrário do cadastro de produto, que deixa
     * o índice único do banco recusar a duplicata. A diferença se defende em uma frase: lá a
     * violação é erro de digitação, traduzido adiante pela camada {@code web/}; aqui a segunda
     * abertura é rotina, porque o caixa de ontem ficou sem fechar, e quem chama precisa distinguir
     * esse caso de qualquer outra falha. O índice único parcial da migration V6 continua existindo
     * como rede, para duas requisições simultâneas que passem juntas por esta checagem.
     *
     * @param valorAbertura zero vale, negativo não; quem recusa é a raiz do agregado
     * @return o id da sessão criada, gerado na aplicação e nunca pelo banco (RNF01, RNF03)
     * @throws OperadorJaTemCaixaAbertoException se o operador já tem uma sessão ABERTA
     * @throws IllegalArgumentException          se {@code valorAbertura} é negativo
     */
    @Transactional
    public UUID abrir(UUID usuarioId, Money valorAbertura) {
        Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");

        if (sessoes.existsByUsuarioIdAndStatus(usuarioId, StatusSessaoCaixa.ABERTA)) {
            throw new OperadorJaTemCaixaAbertoException(usuarioId);
        }

        SessaoCaixa sessao = new SessaoCaixa(usuarioId, valorAbertura);
        return sessoes.save(SessaoCaixaEntity.de(sessao)).getId();
    }

    /**
     * Sangria (RF14): retirada de dinheiro do caixa, com motivo obrigatório.
     *
     * @throws SessaoCaixaNaoEncontradaException se o id não existe nesta conta
     * @throws IllegalArgumentException          se falta motivo, ou se a retirada deixaria o
     *                                           esperado negativo
     * @throws IllegalStateException             se a sessão já está FECHADA
     */
    @Transactional
    public void registrarSangria(UUID sessaoId, Money valor, String motivo) {
        SessaoCaixaEntity linha = buscar(sessaoId);
        SessaoCaixa sessao = linha.paraDominio();

        sessao.sangrar(valor, motivo);

        linha.atualizarCom(sessao);
        sessoes.save(linha);
    }

    /**
     * Suprimento (RF14): reforço de troco, com motivo obrigatório.
     *
     * @throws SessaoCaixaNaoEncontradaException se o id não existe nesta conta
     * @throws IllegalArgumentException          se falta motivo
     * @throws IllegalStateException             se a sessão já está FECHADA
     */
    @Transactional
    public void registrarSuprimento(UUID sessaoId, Money valor, String motivo) {
        SessaoCaixaEntity linha = buscar(sessaoId);
        SessaoCaixa sessao = linha.paraDominio();

        sessao.suprir(valor, motivo);

        linha.atualizarCom(sessao);
        sessoes.save(linha);
    }

    /**
     * Fechamento com conferência (RF15): o operador conta o dinheiro da gaveta e o agregado apura a
     * diferença contra o que deveria estar lá.
     *
     * <p><strong>Devolve a diferença</strong> em vez de {@code void}, ao contrário da sangria e do
     * suprimento. Ela é literalmente a pergunta que se faz ao fechar o caixa, e quem chama teria de
     * consultar a sessão de novo apenas para mostrá-la.
     *
     * <p>Positivo é falta na gaveta, negativo é sobra.
     *
     * @throws SessaoCaixaNaoEncontradaException se o id não existe nesta conta
     * @throws IllegalArgumentException          se {@code valorContado} é negativo
     * @throws IllegalStateException             se a sessão já está FECHADA
     */
    @Transactional
    public Money fechar(UUID sessaoId, Money valorContado) {
        SessaoCaixaEntity linha = buscar(sessaoId);
        SessaoCaixa sessao = linha.paraDominio();

        Money diferenca = sessao.fechar(valorContado);

        linha.atualizarCom(sessao);
        sessoes.save(linha);

        return diferenca;
    }

    /**
     * Histórico de sessões de um dia (RF16), de todos os operadores ou de um só.
     *
     * <p><strong>O dia de uma sessão é o da abertura.</strong> O expediente aberto às 22h de segunda
     * e fechado à 1h de terça é inteiro de segunda. É também a única data que existe enquanto a
     * sessão está ABERTA, então o caixa de hoje aparece aqui antes de qualquer fechamento, o que não
     * aconteceria se o corte fosse por {@code fechada_em}.
     *
     * <p><strong>É aqui que o fuso de referência sai do papel.</strong> A coluna está gravada em
     * UTC, e o dia que o operador quer dizer é o do balcão. Sem essa conversão, toda sessão aberta
     * depois das 21h cairia no dia seguinte: meio expediente no dia errado, sem nada denunciando o
     * erro.
     *
     * @param dia             o dia local do balcão, obrigatório
     * @param operadorOuNulo  o operador, ou {@code null} para o dia inteiro da conta
     */
    @Transactional(readOnly = true)
    public List<ResumoDeSessao> historicoDoDia(LocalDate dia, UUID operadorOuNulo) {
        Instant inicio = FusoDeReferencia.inicioDoDia(dia);
        Instant fim = FusoDeReferencia.inicioDoDiaSeguinte(dia);

        List<LinhaDoHistorico> linhas = operadorOuNulo == null
                ? sessoes.findByAbertaEmGreaterThanEqualAndAbertaEmLessThanOrderByAbertaEm(
                        inicio, fim)
                : sessoes.findByUsuarioIdAndAbertaEmGreaterThanEqualAndAbertaEmLessThanOrderByAbertaEm(
                        operadorOuNulo, inicio, fim);

        return linhas.stream().map(ResumoDeSessao::de).toList();
    }

    /**
     * Uma sessão no histórico, <strong>sem os movimentos</strong>.
     *
     * <p>Aninhado no serviço, como {@code ProdutoService.DadosDoProduto}, porque é o formato de
     * resposta deste caso de uso e de mais nenhum.
     *
     * <p>Não é {@code SessaoCaixa} de propósito. O agregado carrega o extrato do expediente inteiro,
     * e devolver uma lista deles para montar uma tabela de totais traria todo movimento de todo
     * caixa do dia, que é exatamente o custo anunciado pelo carregamento {@code EAGER} de
     * {@code SessaoCaixaEntity}. Quem precisa do extrato de uma sessão carrega aquela sessão.
     *
     * @param diferenca positivo é falta na gaveta, negativo é sobra; nulo enquanto ABERTA, junto
     *                  com {@code valorFechamentoContado} e {@code fechadaEm}
     */
    public record ResumoDeSessao(UUID id, UUID usuarioId, Money valorAbertura,
            Money valorFechamentoEsperado, Money valorFechamentoContado, Money diferenca,
            Instant abertaEm, Instant fechadaEm, StatusSessaoCaixa status) {

        static ResumoDeSessao de(LinhaDoHistorico linha) {
            return new ResumoDeSessao(linha.id(), linha.usuarioId(),
                    Money.de(linha.valorAbertura()), Money.de(linha.valorFechamentoEsperado()),
                    moneyOuNulo(linha.valorFechamentoContado()), moneyOuNulo(linha.diferenca()),
                    linha.abertaEm(), linha.fechadaEm(), linha.status());
        }

        private static Money moneyOuNulo(BigDecimal valor) {
            return valor == null ? null : Money.de(valor);
        }
    }

    /**
     * A sessão de trabalho de um lançamento, sempre por id explícito.
     *
     * <p>Não existe um <em>deduzir a sessão aberta do operador</em>: a dedução só funcionaria
     * enquanto valesse a regra de um caixa aberto por operador, e ficaria muda no dia em que ela
     * mudar. Pedir o id é uma linha a mais em quem chama e uma suposição a menos aqui dentro.
     */
    private SessaoCaixaEntity buscar(UUID sessaoId) {
        Objects.requireNonNull(sessaoId, "id da sessao de caixa nao pode ser nulo");
        return sessoes.findById(sessaoId)
                .orElseThrow(() -> new SessaoCaixaNaoEncontradaException(sessaoId));
    }
}
