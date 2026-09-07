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
 * Cria uma conta com seu usuario administrador (decisoes A3 e D14b).
 *
 * <p>Nao existe cadastro publico no MVP: os dois negocios-piloto sao criados pelo mantenedor. Isso
 * roda pela aplicacao, e nao por {@code INSERT} em SQL, justamente para passar pelas mesmas regras
 * do sistema — o hash BCrypt e a verificacao de senha vazada da A5 vivem aqui dentro e seriam
 * pulados por um script.
 *
 * <p>Uso:
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=seed \
 *   -Dspring-boot.run.arguments="--caixa-simples.seed.nome-negocio=Cafeteria Piloto \
 *     --caixa-simples.seed.tipo-negocio=cafeteria \
 *     --caixa-simples.seed.email=dona@exemplo.com \
 *     --caixa-simples.seed.senha=..."
 * </pre>
 *
 * <p>Rodar duas vezes com o mesmo e-mail nao duplica nada: a segunda execucao encerra sem escrever.
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
            log.info("Ja existe credencial para {} — nada a fazer.", propriedades.email());
            return;
        }

        // Falha antes de gravar qualquer coisa se a senha nao servir (A5).
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
