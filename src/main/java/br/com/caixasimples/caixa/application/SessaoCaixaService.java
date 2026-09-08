package br.com.caixasimples.caixa.application;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.caixa.internal.SessaoCaixaEntity;
import br.com.caixasimples.caixa.internal.SessaoCaixaRepository;
import br.com.caixasimples.shared.Money;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso da sessao de caixa: abrir (RF13), registrar sangria e registrar suprimento (RF14).
 * Passo R07 do roteiro, etapa 1.4 do plano.
 *
 * <p>Cada caso de uso e sempre a mesma sequencia — carrega a linha, deixa a raiz do agregado
 * decidir, grava o que ela decidiu. Nenhuma regra de dinheiro mora aqui: {@link SessaoCaixa} e que
 * sabe que sangria exige motivo (RF14), que nao se retira mais do que ha na gaveta (D22c) e que
 * sessao fechada nao aceita movimento (D22d).
 *
 * <p><strong>A unica regra que mora neste arquivo e a D22a</strong>, e mora aqui por nao caber na
 * raiz: uma sessao nao enxerga as outras sessoes da conta, entao quem pergunta se o operador ja tem
 * caixa aberto tem de ser quem fala com o repositorio.
 *
 * <p><strong>Nao existe fechamento aqui</strong>, e a ausencia e deliberada: RF15, RF16 e a
 * conferencia com {@code valorFechamentoContado} sao o R08.
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
