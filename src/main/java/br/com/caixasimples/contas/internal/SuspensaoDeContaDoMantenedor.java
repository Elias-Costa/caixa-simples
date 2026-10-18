package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.EventoDeSeguranca;
import br.com.caixasimples.shared.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Suspende na hora o acesso de uma Conta, por uso ilegal ou ataque ao serviço, como os termos de uso
 * permitem, e derruba os aparelhos de todos os usuários dela.
 *
 * <p>Com a Conta suspensa, o login com a senha certa responde que o acesso está suspenso, e o filtro do
 * token recusa todo token dela. A versão de acesso de cada Usuário sobe junto, para o reverso, que só
 * limpa a marca, não reviver nenhum token de antes: todos entram de novo. O aviso ao administrador sai
 * do mantenedor, por e-mail, no mesmo dia.
 *
 * <p>Roda apenas sob o perfil {@code suspender-conta}, numa execução avulsa da imagem, como o seed, e
 * pela aplicação, e não por SQL, para passar pelas regras do sistema. O reverso é outro perfil,
 * {@code reativar-conta}: o comando que suspende nunca reativa por um valor trocado no ambiente. Rodar
 * de novo sobre a Conta já suspensa não muda nada, nem o instante em que a suspensão começou.
 *
 * <p>Tenant primeiro, transação depois, como no seed: o Hibernate resolve o tenant na abertura da
 * sessão. A linha da Conta é travada antes dos usuários, a ordem da inativação, e os usuários em ordem
 * de id, para uma troca de senha simultânea não gravar a mesma versão de acesso.
 */
@Component
@Profile("suspender-conta")
@EnableConfigurationProperties(PropriedadesDaSuspensao.class)
class SuspensaoDeContaDoMantenedor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuspensaoDeContaDoMantenedor.class);

    private final PropriedadesDaSuspensao propriedades;
    private final ContaRepository contas;
    private final UsuarioRepository usuarios;
    private final TransactionTemplate transacao;

    SuspensaoDeContaDoMantenedor(PropriedadesDaSuspensao propriedades, ContaRepository contas,
            UsuarioRepository usuarios, TransactionTemplate transacao) {
        this.propriedades = propriedades;
        this.contas = contas;
        this.usuarios = usuarios;
        this.transacao = transacao;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        ContaId contaId = propriedades.contaId();

        // Tenant primeiro, transação depois. Ver o javadoc da classe.
        Desfecho desfecho = TenantContext.executarComo(contaId, () -> transacao.execute(status -> {
            // O id vem do mantenedor, na máquina, e não de um pedido; o tenant já é o dessa Conta.
            Conta conta = contas.buscarParaAtualizar(contaId.valor())
                    .orElseThrow(() -> new IllegalStateException("Nenhuma Conta com esse id"));
            if (conta.isSuspensa()) {
                return new Desfecho(conta.getSuspensaEm(), 0, true);
            }
            // O banco guarda microssegundos; truncado aqui, o instante do log é o gravado.
            conta.suspender(Instant.now().truncatedTo(ChronoUnit.MICROS));
            contas.save(conta);
            List<Usuario> daConta = usuarios.buscarTodosParaAtualizar();
            daConta.forEach(Usuario::revogarAcessos);
            usuarios.saveAll(daConta);
            return new Desfecho(conta.getSuspensaEm(), daConta.size(), false);
        }));

        if (desfecho.jaEstava()) {
            log.info("A Conta {} já estava suspensa desde {}; nada mudou", contaId, desfecho.desde());
            return;
        }
        EventoDeSeguranca.de("conta.suspensa")
                .com("conta", contaId)
                .com("usuarios", desfecho.revogados())
                .registrar();
    }

    /** O que a transação encontrou e fez, para o log sair depois do commit. */
    private record Desfecho(Instant desde, int revogados, boolean jaEstava) {
    }
}
