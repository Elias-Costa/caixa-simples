package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.Perfil;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cria uma conta com o seu usuário administrador.
 *
 * <p>Não existe cadastro público: as contas são criadas pelo mantenedor. Isso roda pela aplicação,
 * e não por {@code INSERT} em SQL, justamente para passar pelas mesmas regras do sistema. O hash
 * BCrypt e a verificação de senha vazada vivem aqui dentro e seriam pulados por um script.
 *
 * <p>Roda apenas sob o perfil {@code seed} e lê os dados da conta de
 * {@link PropriedadesDoSeed}. Executar duas vezes com o mesmo e-mail não duplica nada: a segunda
 * execução encerra sem escrever.
 */
@Component
@Profile("seed")
@EnableConfigurationProperties(PropriedadesDoSeed.class)
class SeedDeConta implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDeConta.class);

    private final PropriedadesDoSeed propriedades;
    private final ContaRepository contas;
    private final UsuarioRepository usuarios;
    private final CredencialRepository credenciais;
    private final PasswordEncoder encoder;
    private final PoliticaDeSenha politica;

    SeedDeConta(PropriedadesDoSeed propriedades, ContaRepository contas, UsuarioRepository usuarios,
            CredencialRepository credenciais, PasswordEncoder encoder, PoliticaDeSenha politica) {
        this.propriedades = propriedades;
        this.contas = contas;
        this.usuarios = usuarios;
        this.credenciais = credenciais;
        this.encoder = encoder;
        this.politica = politica;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments argumentos) {
        exigirPreenchido(propriedades.nomeNegocio(), "caixa-simples.seed.nome-negocio");
        exigirPreenchido(propriedades.email(), "caixa-simples.seed.email");
        exigirPreenchido(propriedades.senha(), "caixa-simples.seed.senha");

        if (credenciais.existsByEmailIgnoreCase(propriedades.email())) {
            log.info("Ja existe credencial para {}; nada a fazer.", propriedades.email());
            return;
        }

        // Falha antes de gravar qualquer coisa se a senha não servir.
        politica.exigirValida(propriedades.senha());

        ContaId contaId = contas
                .save(new Conta(propriedades.nomeNegocio(), propriedades.tipoNegocio(), Plano.GRATIS))
                .contaId();

        Usuario usuario = TenantContext.executarComo(contaId,
                () -> usuarios.save(new Usuario(propriedades.nomeUsuario(), Perfil.ADMIN)));

        credenciais.save(new Credencial(propriedades.email(),
                encoder.encode(propriedades.senha()), usuario.getId(), contaId));

        log.info("Conta {} criada para {}", contaId, propriedades.email());
    }

    private static void exigirPreenchido(String valor, String propriedade) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Informe " + propriedade);
        }
    }
}
