package br.com.caixasimples.contas.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Liga as mensalidades e o segredo do ambiente à assinatura dos códigos de pedido. Em toda subida,
 * inclusive na execução avulsa que só cria uma Conta: assim a falta de uma das variáveis aparece
 * no primeiro uso da imagem, e não no primeiro pedido de plano.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConfiguracaoDosPlanos.class)
class PlanosConfiguration {

    @Bean
    AssinaturaDePedido assinaturaDePedido(ConfiguracaoDosPlanos configuracao) {
        return new AssinaturaDePedido(configuracao.segredo());
    }
}
