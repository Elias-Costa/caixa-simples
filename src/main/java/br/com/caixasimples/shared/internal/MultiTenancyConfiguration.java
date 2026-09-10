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
 * Liga o {@link TenantContext} ao Hibernate. É o que faz {@code @TenantId} funcionar de fato.
 *
 * <p>Com o resolver registrado, o Hibernate aplica o filtro por {@code conta_id} em toda query
 * JPQL e Criteria, e preenche a coluna no insert, sem nenhum {@code WHERE conta_id = ?} escrito à
 * mão. É assim que o isolamento entre contas é implementado (RF28, RNF05), e é o motivo de não
 * existir filtro manual em repositório nenhum: uma consulta nova nasce filtrada por construção.
 *
 * <p>O resolver <strong>não</strong> alcança SQL nativo. Por isso query nativa em código de
 * negócio é proibida neste projeto: ela passaria por fora do filtro sem que nada avisasse.
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
     * Devolve a conta do contexto, ou {@link TenantContext#SEM_TENANT} quando não há nenhuma.
     *
     * <p>Não pode devolver {@code null}: o Hibernate 7 recusa abrir sessão sem identificador de
     * tenant depois que um resolver é registrado, e o contexto nem sobe, já que o Spring Data
     * valida as derived queries na inicialização, quando ainda não existe requisição.
     *
     * <p>O sentinela não é relaxamento de isolamento, é o oposto: falha fechado. Fluxos legítimos
     * rodam sem tenant, como a criação de conta, a leitura do catálogo de referência da plataforma
     * e as migrations do Flyway, e continuam funcionando porque essas tabelas não têm
     * {@code @TenantId}. Já uma entidade que <em>tem</em> {@code @TenantId} se comporta assim: na
     * leitura, filtra por um {@code conta_id} que nenhuma linha possui e devolve vazio; na escrita,
     * a foreign key {@code conta_id REFERENCES conta (id)} rejeita o insert. Quem precisa de conta
     * e não pode seguir sem ela usa {@link TenantContext#exigirAtual()} e falha alto antes disso.
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
