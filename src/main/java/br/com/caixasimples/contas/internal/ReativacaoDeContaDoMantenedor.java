package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.EventoDeSeguranca;
import br.com.caixasimples.shared.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Devolve o acesso a uma Conta suspensa pela {@link SuspensaoDeContaDoMantenedor}.
 *
 * <p>Só limpa a marca. Os tokens de antes da suspensão continuam recusados, pela versão de acesso que
 * a suspensão subiu, e todos os usuários entram de novo com as próprias senhas. O que os aparelhos
 * registraram sem rede durante a suspensão continua na fila de cada um e sai quando o mesmo usuário
 * entrar ali.
 *
 * <p>Roda apenas sob o perfil {@code reativar-conta}, numa execução avulsa da imagem, como o seed. A
 * Conta que não está suspensa termina sem efeito. A linha da Conta é travada, como na suspensão, com o
 * tenant definido antes da transação.
 */
@Component
@Profile("reativar-conta")
@EnableConfigurationProperties(PropriedadesDaSuspensao.class)
class ReativacaoDeContaDoMantenedor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReativacaoDeContaDoMantenedor.class);

    private final PropriedadesDaSuspensao propriedades;
    private final ContaRepository contas;
    private final TransactionTemplate transacao;

    ReativacaoDeContaDoMantenedor(PropriedadesDaSuspensao propriedades, ContaRepository contas,
            TransactionTemplate transacao) {
        this.propriedades = propriedades;
        this.contas = contas;
        this.transacao = transacao;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        ContaId contaId = propriedades.contaId();

        boolean reativada = TenantContext.executarComo(contaId, () -> transacao.execute(status -> {
            // O id vem do mantenedor, na máquina, e não de um pedido; o tenant já é o dessa Conta.
            Conta conta = contas.buscarParaAtualizar(contaId.valor())
                    .orElseThrow(() -> new IllegalStateException("Nenhuma Conta com esse id"));
            if (!conta.isSuspensa()) {
                return false;
            }
            conta.reativar();
            contas.save(conta);
            return true;
        }));

        if (!reativada) {
            log.info("A Conta {} não estava suspensa; nada mudou", contaId);
            return;
        }
        EventoDeSeguranca.de("conta.reativada").com("conta", contaId).registrar();
    }
}
