package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.internal.Conta;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.PrimeiroAcessoDaConta;
import br.com.caixasimples.contas.MovimentosDeEstoqueDaConta;
import br.com.caixasimples.contas.RecursoDoPlano;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso da Conta em operação: consultar e configurar o estoque, marcar o primeiro acesso e
 * conferir se o plano dá direito a um recurso.
 *
 * <p><strong>Nenhum método recebe a conta como parâmetro, e essa ausência é a regra.</strong>
 * {@code Conta} é a única entidade de negócio sem filtro automático de tenant, porque o id dela
 * <em>é</em> o tenant; em troca, toda leitura parte de {@code TenantContext.exigirAtual()} e
 * nunca de um id que alguém informe (RNF05). Quem precisa saber algo de outra conta não tem por
 * onde perguntar.
 *
 * <p>O módulo de estoque pergunta se a conta ligou o controle (RF17) antes de dar baixa numa
 * venda concluída. O login chama a marca do primeiro acesso (RF32) dentro deste módulo, que
 * publica o fato para o cadastro reagir sem receber uma chamada direta de escrita. Estoque e
 * relatórios perguntam pelo recurso do plano antes de cada caso de uso pago.
 */
@Service
public class ContaService {

    private final ContaRepository contas;
    private final ApplicationEventPublisher eventos;
    private final MovimentosDeEstoqueDaConta movimentos;

    ContaService(ContaRepository contas, ApplicationEventPublisher eventos,
            MovimentosDeEstoqueDaConta movimentos) {
        this.contas = contas;
        this.eventos = eventos;
        this.movimentos = movimentos;
    }

    /**
     * Se a conta em operação ligou o controle de estoque (RF17). Conta nova nasce com ele
     * desligado, porque negócio baseado em serviço não precisa dele.
     *
     * @throws br.com.caixasimples.shared.TenantNaoResolvidoException se não há conta no contexto
     * @throws IllegalStateException se a conta do contexto não existe, o que só acontece com um
     *         evento que carrega uma conta apagada do banco por fora da aplicação
     */
    @Transactional(readOnly = true)
    public boolean estoqueHabilitado() {
        ContaId contaId = TenantContext.exigirAtual();
        Conta conta = contas.findById(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
        return conta.isEstoqueHabilitado();
    }

    /**
     * Recusa a operação se o plano da Conta não inclui o recurso, ou se os recursos pagos estão
     * suspensos por falta de renovação. Na tolerância depois do vencimento, o recurso ainda vale.
     *
     * <p>Quem chama confere o perfil antes: o operador recebe a recusa de perfil, e a Conta sem o
     * recurso, a do plano. O dia é o de hoje no balcão, porque a suspensão começa à meia-noite
     * de lá, e não do servidor.
     *
     * @throws RecursoForaDoPlanoException se o plano não inclui o recurso
     * @throws PlanoSuspensoException se o plano inclui o recurso, mas está suspenso
     */
    @Transactional(readOnly = true)
    public void exigirRecurso(RecursoDoPlano recurso) {
        exigirRecurso(contaDoContexto(), recurso);
    }

    private static void exigirRecurso(Conta conta, RecursoDoPlano recurso) {
        Objects.requireNonNull(recurso, "recurso nao pode ser nulo");
        LocalDate hoje = LocalDate.now(FusoDeReferencia.DO_BALCAO);
        if (!conta.getPlano().inclui(recurso)) {
            throw new RecursoForaDoPlanoException(recurso, conta.getPlano());
        }
        if (!conta.temRecurso(recurso, hoje)) {
            throw new PlanoSuspensoException(conta.inicioDaSuspensao());
        }
    }

    private Conta contaDoContexto() {
        ContaId contaId = TenantContext.exigirAtual();
        return contas.findById(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
    }

    /** A configuração é restrita ao administrador, embora a pergunta operacional seja pública. */
    @Transactional(readOnly = true)
    public boolean configuracaoDeEstoque() {
        UsuarioContext.exigirAdmin();
        return estoqueHabilitado();
    }

    /**
     * Altera o controle da Conta autenticada (RF17). Depois do primeiro movimento, desligar faria
     * as vendas continuarem sem atualizar o saldo; a checagem consulta também produtos inativos.
     *
     * <p>Ligar exige o recurso de estoque do plano. Desligar não: a Conta sem o recurso ainda pode
     * desligar um controle que ficou ligado, enquanto não houver movimento.
     *
     * @throws RecursoForaDoPlanoException se liga sem o plano que inclui o estoque
     * @throws PlanoSuspensoException se liga com os recursos pagos suspensos
     */
    @Transactional
    public boolean definirEstoqueHabilitado(boolean habilitado) {
        UsuarioContext.exigirAdmin();
        ContaId contaId = TenantContext.exigirAtual();
        Conta conta = contas.buscarParaAtualizar(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
        if (habilitado && !conta.isEstoqueHabilitado()) {
            exigirRecurso(conta, RecursoDoPlano.ESTOQUE);
        }
        if (!habilitado && conta.isEstoqueHabilitado() && movimentos.existem()) {
            throw new EstoqueComMovimentosException();
        }
        conta.definirEstoqueHabilitado(habilitado);
        return conta.isEstoqueHabilitado();
    }

    /**
     * Aplica a oferta no primeiro login de ADMIN. O bloqueio da linha da Conta torna a marca
     * suficiente mesmo com dois logins simultâneos, e o ouvinte síncrono copia antes do commit.
     */
    @Transactional
    public void registrarPrimeiroAcessoSeNecessario() {
        UsuarioContext.exigirAdmin();
        ContaId contaId = TenantContext.exigirAtual();
        Conta conta = contas.buscarParaAtualizar(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
        if (conta.isCatalogoInicialAplicado()) {
            return;
        }
        conta.marcarCatalogoInicialAplicado();
        eventos.publishEvent(new PrimeiroAcessoDaConta(contaId.valor(), conta.getTipoNegocio()));
    }
}
