package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.shared.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Socorre a Conta cujo administrador perdeu o acesso: define para ele uma senha provisória e
 * derruba os aparelhos de todos os usuários da Conta.
 *
 * <p>Sem recuperação de senha por e-mail, é o único caminho do administrador que esqueceu a senha,
 * ou que foi trancado fora por quem a descobriu e a trocou. Derrubar todos os usuários, e não só o
 * administrador, tira também quem tomou a Conta de um operador cuja senha ele tenha redefinido. A
 * senha provisória foi escolhida pelo mantenedor, então passa pela política com a recusa quando a
 * lista de vazamentos não responde, e o aplicativo pede a troca na próxima entrada.
 *
 * <p>Roda apenas sob o perfil {@code redefinir-senha}, numa execução avulsa da imagem, como o seed,
 * e pela aplicação, e não por SQL, para passar pelas regras do sistema. Só aceita o e-mail de um
 * administrador: o operador é socorrido pelo administrador da própria Conta.
 *
 * <p>Tenant primeiro, transação depois, como no seed: o Hibernate resolve o tenant na abertura da
 * sessão. Os usuários são travados antes da revogação, para uma troca de senha simultânea não gravar
 * a mesma versão de acesso.
 */
@Component
@Profile("redefinir-senha")
@EnableConfigurationProperties(PropriedadesDaRedefinicao.class)
class RedefinicaoDeSenhaDoMantenedor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RedefinicaoDeSenhaDoMantenedor.class);

    private final PropriedadesDaRedefinicao propriedades;
    private final UsuarioRepository usuarios;
    private final CredencialRepository credenciais;
    private final PasswordEncoder encoder;
    private final PoliticaDeSenha politica;
    private final TransactionTemplate transacao;

    RedefinicaoDeSenhaDoMantenedor(PropriedadesDaRedefinicao propriedades,
            UsuarioRepository usuarios, CredencialRepository credenciais, PasswordEncoder encoder,
            PoliticaDeSenha politica, TransactionTemplate transacao) {
        this.propriedades = propriedades;
        this.usuarios = usuarios;
        this.credenciais = credenciais;
        this.encoder = encoder;
        this.politica = politica;
        this.transacao = transacao;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        exigirPreenchido(propriedades.email(), "fechadia.redefinicao.email");
        exigirPreenchido(propriedades.senha(), "fechadia.redefinicao.senha");

        Credencial credencial = credenciais.findByEmailIgnoreCase(propriedades.email().trim())
                .orElseThrow(() -> new IllegalStateException("Nenhum login com esse e-mail"));
        ContaId contaId = credencial.getContaId();

        // A credencial só existe para usuário ativo, porque a inativação a apaga.
        boolean administrador = TenantContext.executarComo(contaId,
                () -> usuarios.findById(credencial.getUsuarioId()))
                .map(usuario -> usuario.getPerfil() == Perfil.ADMIN)
                .orElse(false);
        if (!administrador) {
            throw new IllegalStateException("O e-mail não é de um administrador; o operador é "
                    + "socorrido pelo administrador da própria Conta");
        }

        // Falha antes de gravar qualquer coisa se a senha não servir.
        politica.exigirValida(propriedades.senha());
        String senhaHash = encoder.encode(propriedades.senha());

        // Tenant primeiro, transação depois. Ver o javadoc da classe.
        int revogados = TenantContext.executarComo(contaId, () -> transacao.execute(status -> {
            List<Usuario> daConta = usuarios.buscarTodosParaAtualizar();
            daConta.forEach(Usuario::revogarAcessos);
            usuarios.saveAll(daConta);
            Credencial paraGravar = credenciais.findById(credencial.getId())
                    .orElseThrow(() -> new IllegalStateException("o login sumiu durante a execução"));
            paraGravar.definirSenhaProvisoria(senhaHash);
            credenciais.save(paraGravar);
            return daConta.size();
        }));

        log.info("Senha provisória definida na Conta {}; acessos de {} usuários revogados", contaId,
                revogados);
    }

    private static void exigirPreenchido(String valor, String propriedade) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Informe " + propriedade);
        }
    }
}
