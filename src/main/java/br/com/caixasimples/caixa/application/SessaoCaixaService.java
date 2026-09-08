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
 * Casos de uso da sessao de caixa: abrir (RF13), registrar sangria e suprimento (RF14), fechar com
 * conferencia (RF15) e consultar o historico por operador e por dia (RF16). Passos R07 e R08 do
 * roteiro, etapa 1.4 do plano.
 *
 * <p>Cada caso de uso de escrita e sempre a mesma sequencia — carrega a linha, deixa a raiz do
 * agregado decidir, grava o que ela decidiu. Nenhuma regra de dinheiro mora aqui:
 * {@link SessaoCaixa} e que sabe que sangria exige motivo (RF14), que nao se retira mais do que ha
 * na gaveta (D22c), que sessao fechada nao aceita movimento (D22d) e que ela tampouco fecha de novo
 * (D23e).
 *
 * <p><strong>Duas regras moram neste arquivo, e as duas por nao caberem na raiz:</strong> a D22a,
 * porque uma sessao nao enxerga as outras sessoes da conta — quem pergunta se o operador ja tem
 * caixa aberto tem de ser quem fala com o repositorio; e a D23a, porque delimitar <em>o dia</em>
 * e traduzir uma data de calendario para um intervalo de instantes, e isso e trabalho de quem monta
 * a consulta, nao de um agregado que so conhece o proprio expediente.
 *
 * <p><strong>{@link #fechar} nao pergunta quem esta fechando</strong> (D23b): o {@code @TenantId} ja
 * garante que a sessao e da propria conta (RNF05), e a autorizacao por perfil e o R22, que e onde
 * perfil existe. Ate la, nada impede um operador de fechar o caixa do colega — e o custo aceito,
 * registrado para nao passar por esquecimento.
 *
 * <p>O {@code usuarioId} chega como parametro porque ainda nao ha camada {@code web/} neste modulo.
 * Quando ela nascer (R23), o valor vem do claim do JWT autenticado e nunca do payload (RNF05) — o
 * mesmo que ja vale para o {@code contaId}, que nao aparece em assinatura nenhuma deste arquivo.
 */
@Service
public class SessaoCaixaService {

    private final SessaoCaixaRepository sessoes;

    SessaoCaixaService(SessaoCaixaRepository sessoes) {
        this.sessoes = sessoes;
    }

    /**
     * Abertura de caixa (RF13): o operador informa o que ha na gaveta e a sessao nasce ABERTA, com
     * o esperado ja igual a esse valor.
     *
     * <p><strong>Consulta antes de gravar, ao contrario do que o R03 decidiu para o codigo do
     * produto</strong> — e a diferenca se defende em uma frase: la a violacao e erro de digitacao,
     * traduzido la na frente pela camada {@code web/}; aqui a segunda abertura e rotina (o caixa de
     * ontem ficou sem fechar) e quem chama precisa distinguir esse caso de qualquer outra falha.
     * O indice unico parcial da V6 continua existindo como rede, para duas requisicoes simultaneas
     * que passem juntas por esta checagem.
     *
     * @param valorAbertura zero vale, negativo nao (D22b) — quem recusa e a raiz
     * @return o id da sessao criada — gerado na aplicacao, nunca pelo banco (RNF01/RNF03)
     * @throws OperadorJaTemCaixaAbertoException se o operador ja tem uma sessao ABERTA (D22a)
     * @throws IllegalArgumentException          se {@code valorAbertura} e negativo (D22b)
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
     * Sangria (RF14): retirada de dinheiro do caixa, com motivo obrigatorio.
     *
     * @throws SessaoCaixaNaoEncontradaException se o id nao existe nesta conta
     * @throws IllegalArgumentException          se falta motivo, ou se a retirada deixaria o
     *                                           esperado negativo (D22c)
     * @throws IllegalStateException             se a sessao ja esta FECHADA (D22d)
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
     * Suprimento (RF14): reforco de troco, com motivo obrigatorio.
     *
     * @throws SessaoCaixaNaoEncontradaException se o id nao existe nesta conta
     * @throws IllegalArgumentException          se falta motivo
     * @throws IllegalStateException             se a sessao ja esta FECHADA (D22d)
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
     * Fechamento com conferencia (RF15): o operador conta o dinheiro da gaveta e o agregado apura a
     * diferenca contra o que deveria estar la.
     *
     * <p><strong>Devolve a diferenca</strong> em vez de {@code void}, ao contrario da sangria e do
     * suprimento: e literalmente a pergunta que se faz ao fechar o caixa — <em>bateu?</em> — e quem
     * chama teria de consultar a sessao de novo so para mostra-la.
     *
     * <p>Positivo e falta na gaveta, negativo e sobra (dicionario de dados §3).
     *
     * @throws SessaoCaixaNaoEncontradaException se o id nao existe nesta conta
     * @throws IllegalArgumentException          se {@code valorContado} e negativo (D23d)
     * @throws IllegalStateException             se a sessao ja esta FECHADA (D23e)
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
     * Historico de sessoes de um dia (RF16), de todos os operadores ou de um so.
     *
     * <p><strong>O dia de uma sessao e o da abertura</strong> (D23a): o expediente aberto as 22h de
     * segunda e fechado a 1h de terca e inteiro de segunda. E tambem a unica data que existe
     * enquanto a sessao esta ABERTA, entao o caixa de hoje aparece aqui antes de qualquer
     * fechamento — o que nao aconteceria se o corte fosse por {@code fechada_em}.
     *
     * <p><strong>E aqui que a P6 sai do papel.</strong> A coluna esta gravada em UTC; o dia que o
     * operador quer dizer e o do balcao, em {@code America/Bahia}. Sem essa conversao, toda sessao
     * aberta depois das 21h cairia no dia seguinte — meio expediente no dia errado, sem nada
     * denunciando o erro.
     *
     * @param dia             o dia local do balcao, obrigatorio
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
     * Uma sessao no historico, <strong>sem os movimentos</strong> (D23c).
     *
     * <p>Aninhado no servico, como {@code ProdutoService.DadosDoProduto}: e o formato de resposta
     * deste caso de uso e de mais nenhum.
     *
     * <p>Nao e {@code SessaoCaixa} de proposito. O agregado carrega o extrato do expediente inteiro,
     * e devolver uma lista deles para montar uma tabela de totais traria todo movimento de todo
     * caixa do dia — que e exatamente o custo que o {@code EAGER} de {@code SessaoCaixaEntity}
     * anunciou. Quem precisa do extrato de uma sessao carrega aquela sessao.
     *
     * @param diferenca positivo e falta na gaveta, negativo e sobra; nulo enquanto ABERTA, junto com
     *                  {@code valorFechamentoContado} e {@code fechadaEm}
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
     * A sessao de trabalho de um lancamento, sempre por id explicito.
     *
     * <p>Nao existe um <em>deduzir a sessao aberta do operador</em>: a deducao so funcionaria
     * enquanto a D22a valesse, e ficaria muda no dia em que ela mudar. Pedir o id e uma linha a
     * mais em quem chama e uma suposicao a menos aqui dentro.
     */
    private SessaoCaixaEntity buscar(UUID sessaoId) {
        Objects.requireNonNull(sessaoId, "id da sessao de caixa nao pode ser nulo");
        return sessoes.findById(sessaoId)
                .orElseThrow(() -> new SessaoCaixaNaoEncontradaException(sessaoId));
    }
}
