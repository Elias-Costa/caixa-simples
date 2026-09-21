package br.com.caixasimples.shared.web;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Entrega o aplicativo (PWA) a partir dos recursos estáticos do jar.
 *
 * <p>O front-end é compilado pelo Maven na fase de empacotamento e copiado para {@code static/}
 * dentro do jar, na mesma origem da API: sem CORS, sem token cruzando origem e com o service
 * worker no mesmo escopo de {@code /api}. Em desenvolvimento esses arquivos não existem no
 * classpath, porque o servidor do Vite os serve e faz proxy de {@code /api} para cá.
 *
 * <p>O roteador do cliente define rotas que o servidor não conhece, como {@code /caixa} ou
 * {@code /vendas/123}. Ao recarregar a página ou abrir um link direto, o navegador pede esse
 * caminho ao servidor, e a resposta certa é o shell, {@code index.html}, para o roteador do
 * cliente decidir a tela. O que nunca recebe o shell: caminho sob {@code api/}, que é da API e
 * responde 404 quando não existe, e caminho com extensão, que é um arquivo pedido pelo nome e
 * também deve ser 404 se não existir, em vez de um HTML que o navegador não saberia interpretar.
 *
 * <p>Os cabeçalhos de cache são explícitos porque, sem eles, o navegador aplica cache heurístico
 * pela data do arquivo e poderia segurar um {@code index.html} antigo apontando para scripts que
 * já não existem. O shell não entra em cache HTTP; os arquivos de {@code assets/} têm o hash do
 * conteúdo no nome, então cada versão é um nome novo e a anterior pode ser imutável por um ano.
 * Quem faz o aplicativo abrir sem rede é o service worker, não o cache HTTP.
 *
 * <p>O manifest se chama {@code manifest.json}, e não {@code manifest.webmanifest}, porque nem o
 * Spring nem o Tomcat conhecem a segunda extensão e a serviriam como binário genérico; com a
 * primeira, os dois já respondem {@code application/json}, que o navegador aceita como manifest.
 */
@Configuration(proxyBeanMethods = false)
class PwaConfiguration implements WebMvcConfigurer {

    private static final String RAIZ_DO_APLICATIVO = "classpath:/static/";
    private static final String SHELL = "index.html";

    /**
     * A raiz do site é o shell. O tratador de recursos abaixo não atende caminho vazio, então a
     * raiz é encaminhada por nome, sem depender da página de boas-vindas que o Spring Boot
     * configura sozinho.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registro) {
        registro.addViewController("/").setViewName("forward:/" + SHELL);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registro) {
        registro.addResourceHandler("/assets/**")
                .addResourceLocations(RAIZ_DO_APLICATIVO + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable());

        registro.addResourceHandler("/**")
                .addResourceLocations(RAIZ_DO_APLICATIVO)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new ShellComoFallback());
    }

    /**
     * Devolve o arquivo pedido quando ele existe, e o shell quando o caminho é rota do cliente.
     *
     * <p>Estende o resolvedor padrão em vez de reescrevê-lo para manter a proteção dele contra
     * caminho que escapa da raiz.
     */
    private static final class ShellComoFallback extends PathResourceResolver {

        @Override
        protected Resource getResource(String caminho, Resource raiz) throws IOException {
            Resource pedido = super.getResource(caminho, raiz);
            if (pedido != null) {
                return pedido;
            }
            if (ehDaApi(caminho) || ultimoSegmentoTemExtensao(caminho)) {
                return null;
            }
            return super.getResource(SHELL, raiz);
        }

        private static boolean ehDaApi(String caminho) {
            return caminho.equals("api") || caminho.startsWith("api/");
        }

        private static boolean ultimoSegmentoTemExtensao(String caminho) {
            int inicioDoUltimoSegmento = caminho.lastIndexOf('/') + 1;
            return caminho.indexOf('.', inicioDoUltimoSegmento) >= 0;
        }
    }
}
