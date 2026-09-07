package br.com.caixasimples.shared.internal;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import java.util.UUID;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Liga o {@link TenantContext} ao Hibernate — e o que faz {@code @TenantId} funcionar de fato.
 *
 * <p>Com o resolver registrado, o Hibernate aplica o filtro por {@code conta_id} em toda query
 * JPQL/Criteria e preenche a coluna no insert, sem nenhum {@code WHERE conta_id = ?} escrito a
 * mao. Isso e a implementacao de RF28/RNF05 (arquitetura §3) e o motivo de nao existir filtro
 * manual em repositorio nenhum.
 *
 * <p>O resolver <strong>nao</strong> alcanca SQL nativo — por isso query nativa em codigo de
 * negocio e proibida em {@code .claude/rules/multi-tenancy.md}.
 */
@Configuration(proxyBeanMethods = false)
class MultiTenancyConfiguration {

    @Bean
    CurrentTenantIdentifierResolver<UUID> contaTenantIdentifierResolver() {
        return new ContaTenantIdentifierResolver();
    }

    @Bean
    HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer(
            CurrentTenantIdentifierResolver<UUID> resolver) {
        return properties -> properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }

    /**
     * Devolve a conta do contexto, ou {@link TenantContext#SEM_TENANT} quando nao ha nenhuma.
     *
     * <p>Nao pode devolver {@code null}: o Hibernate 7 recusa abrir sessao sem identificador de
     * tenant depois que um resolver e registrado, e o contexto nem sobe (o Spring Data valida as
     * derived queries na inicializacao, quando ainda nao existe requisicao).
     *
     * <p>O sentinela nao e relaxamento de isolamento — e o oposto, falha fechado. Fluxos legitimos
     * rodam sem tenant (criacao de conta no cadastro inicial, leitura de {@code ModeloProduto},
     * migrations do Flyway) e continuam funcionando porque essas tabelas nao tem {@code @TenantId}.
     * Já uma entidade que <em>tem</em> {@code @TenantId}: na leitura, filtra por um
     * {@code conta_id} que nenhuma linha possui e devolve vazio; na escrita, a foreign key
     * {@code conta_id REFERENCES conta (id)} rejeita o insert. Quem precisa de conta e nao pode
     * seguir sem ela usa {@link TenantContext#exigirAtual()} e falha alto antes disso.
     */
    static final class ContaTenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

        @Override
        public UUID resolveCurrentTenantIdentifier() {
            return TenantContext.atual().orElse(TenantContext.SEM_TENANT).valor();
        }

        @Override
        public boolean validateExistingCurrentSessions() {
            return false;
        }
    }
}
