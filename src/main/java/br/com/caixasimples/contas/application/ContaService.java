package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.internal.Conta;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.PrimeiroAcessoDaConta;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso da Conta em operação: responder sobre o estoque e marcar o primeiro acesso.
 *
 * <p><strong>Nenhum método recebe a conta como parâmetro, e essa ausência é a regra.</strong>
 * {@code Conta} é a única entidade de negócio sem filtro automático de tenant, porque o id dela
 * <em>é</em> o tenant; em troca, toda leitura parte de {@code TenantContext.exigirAtual()} e
 * nunca de um id que alguém informe (RNF05). Quem precisa saber algo de outra conta não tem por
 * onde perguntar.
 *
 * <p>O módulo de estoque pergunta se a conta ligou o controle (RF17) antes de dar baixa numa
 * venda concluída. O login chama a marca do primeiro acesso (RF32) dentro deste módulo, que
 * publica o fato para o cadastro reagir sem receber uma chamada direta de escrita.
 */
@Service
public class ContaService {

    private final ContaRepository contas;
    private final ApplicationEventPublisher eventos;

    ContaService(ContaRepository contas, ApplicationEventPublisher eventos) {
        this.contas = contas;
        this.eventos = eventos;
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
     * Aplica a oferta no primeiro login de ADMIN. O bloqueio da linha da Conta torna a marca
     * suficiente mesmo com dois logins simultâneos, e o ouvinte síncrono copia antes do commit.
     */
    @Transactional
    public void registrarPrimeiroAcessoSeNecessario() {
        UsuarioContext.exigirAdmin();
        ContaId contaId = TenantContext.exigirAtual();
        Conta conta = contas.buscarParaPrimeiroAcesso(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
        if (conta.isCatalogoInicialAplicado()) {
            return;
        }
        conta.marcarCatalogoInicialAplicado();
        eventos.publishEvent(new PrimeiroAcessoDaConta(contaId.valor(), conta.getTipoNegocio()));
    }
}
