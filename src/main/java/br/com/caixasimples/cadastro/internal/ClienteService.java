package br.com.caixasimples.cadastro.internal;

import br.com.caixasimples.shared.ContaId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro de cliente (RF03), edicao (RF04) e inativacao (RF05) — passo R04 do roteiro, etapa 1.2
 * do plano.
 *
 * <p><strong>Este arquivo e o slice inteiro</strong>: caso de uso, entidade e repositorio moram
 * aqui, e so o caso de uso e publico. Nao ha {@code domain/} nem mapper para cliente de proposito —
 * ele nao tem invariante nenhuma a proteger, e a arquitetura §1 e §2 chamam de cerimonia dar
 * estrutura de agregado a uma entidade assim. E o mesmo julgamento ja aplicado a {@code Conta} e
 * {@code Usuario} na P7.
 *
 * <p>Mora em {@code internal} porque carrega o {@code @Entity}, e JPA vive em {@code internal}. A
 * camada {@code web} nasce depois no mesmo modulo e alcanca esta classe sem violar fronteira;
 * outro modulo nao alcanca — pelo Modulith, todo subpacote e interno ao modulo.
 *
 * <p>Nao existe busca por nome nem vinculo com venda aqui: a busca do RF06 e da etapa 1.6, e a
 * venda referencia o cliente so por UUID, a partir do R11.
 */
@Service
public class ClienteService {

    private final ClienteRepository clientes;

    ClienteService(ClienteRepository clientes) {
        this.clientes = clientes;
    }

    /**
     * Cadastro de cliente novo (RF03).
     *
     * @return o id do cliente criado — gerado na aplicacao, nunca pelo banco (RNF01/RNF03)
     */
    @Transactional
    public UUID cadastrar(DadosDoCliente dados) {
        Objects.requireNonNull(dados, "dados do cliente nao podem ser nulos");

        return clientes.save(new ClienteEntity(dados.nome(), dados.contato())).getId();
    }

    /**
     * Edicao do cadastro (RF04). Substitui os dois campos pelo que veio em {@code dados}.
     *
     * @throws ClienteNaoEncontradoException se o id nao existe nesta conta
     * @throws IllegalStateException         se o cliente ja foi inativado (D19c)
     */
    @Transactional
    public void editar(UUID id, DadosDoCliente dados) {
        Objects.requireNonNull(dados, "dados do cliente nao podem ser nulos");

        ClienteEntity linha = buscar(id);
        linha.alterar(dados.nome(), dados.contato());
        clientes.save(linha);
    }

    /**
     * Inativacao (RF05) — soft delete, nunca {@code DELETE}: o cliente some da listagem e o
     * registro fica, para a venda antiga nao perder a referencia.
     *
     * <p>Idempotente (D19c): inativar de novo o que ja esta inativo nao estoura.
     *
     * @throws ClienteNaoEncontradoException se o id nao existe nesta conta
     */
    @Transactional
    public void inativar(UUID id) {
        ClienteEntity linha = buscar(id);
        linha.inativar();
        clientes.save(linha);
    }

    /**
     * Traz de volta um cliente inativado (D19c). Idempotente pelo mesmo motivo da inativacao.
     *
     * <p>Existe aqui e nao existe em produto (D17b) porque a razao de la e o indice unico de
     * {@code codigo} entre ativos, que a reativacao poderia violar. A tabela {@code cliente} nao
     * tem indice unico nenhum, entao a operacao nao esbarra em nada.
     *
     * @throws ClienteNaoEncontradoException se o id nao existe nesta conta
     */
    @Transactional
    public void reativar(UUID id) {
        ClienteEntity linha = buscar(id);
        linha.reativar();
        clientes.save(linha);
    }

    /** Clientes da conta. Cliente inativado nao aparece aqui — e o efeito visivel do RF05. */
    @Transactional(readOnly = true)
    public List<Cliente> listarAtivos() {
        return clientes.findByAtivoTrue().stream().map(ClienteEntity::paraCliente).toList();
    }

    private ClienteEntity buscar(UUID id) {
        Objects.requireNonNull(id, "id do cliente nao pode ser nulo");
        return clientes.findById(id).orElseThrow(() -> new ClienteNaoEncontradoException(id));
    }

    /**
     * Os campos de um cliente, na ordem em que uma tela de cadastro os apresenta.
     *
     * <p>Record em vez de dois parametros soltos porque os dois sao {@code String}: trocar um pelo
     * outro na chamada compilaria em silencio.
     *
     * @param nome    obrigatorio
     * @param contato opcional (D19a) — telefone ou e-mail; em branco vira ausente
     */
    public record DadosDoCliente(String nome, String contato) {
    }

    /**
     * O que a listagem devolve.
     *
     * <p>Existe para a entidade continuar em visibilidade de pacote: quem chama o caso de uso
     * recebe um valor, nao a linha do banco.
     */
    public record Cliente(UUID id, String nome, String contato) {
    }

    /**
     * Nao existe cliente com esse id <strong>nesta conta</strong>.
     *
     * <p>Como em {@code ProdutoNaoEncontradoException}, um id de outra conta e indistinguivel de um
     * id que nunca existiu: o filtro de {@code @TenantId} faz a linha nao voltar do banco (RNF05).
     */
    public static class ClienteNaoEncontradoException extends RuntimeException {

        public ClienteNaoEncontradoException(UUID id) {
            super("cliente nao encontrado nesta conta: " + id);
        }
    }
}

/**
 * Linha da tabela {@code cliente}, com a regra dentro — no molde de {@code Usuario} (P7).
 *
 * <p>Visibilidade de pacote de proposito: quem esta fora do slice fala com {@link ClienteService},
 * nunca com a linha.
 *
 * <p>{@code contaId} e preenchido pelo Hibernate a partir do
 * {@code CurrentTenantIdentifierResolver} e filtra toda consulta automaticamente
 * ({@link TenantId}). Nao ha construtor nem setter que o receba — {@code contaId} nunca vem de fora
 * da aplicacao (RNF05).
 */
@Entity
@Table(name = "cliente")
class ClienteEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(nullable = false)
    private String nome;

    /** D19a — opcional: telefone ou e-mail. Em branco e gravado como ausente, nunca vazio. */
    private String contato;

    /** Soft delete (RF05) — preserva a referencia das vendas ja registradas para este cliente. */
    @Column(nullable = false)
    private boolean ativo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected ClienteEntity() {
        // exigido pelo JPA
    }

    ClienteEntity(String nome, String contato) {
        this.id = UUID.randomUUID();
        this.nome = exigirNome(nome);
        this.contato = normalizarContato(contato);
        this.ativo = true;
        this.criadoEm = Instant.now();
    }

    /**
     * Edicao (RF04).
     *
     * <p>Recusa cliente inativo (D19c): sem reativar antes, a alteracao mudaria em silencio um
     * registro que nao aparece em listagem nenhuma.
     */
    void alterar(String nome, String contato) {
        if (!ativo) {
            throw new IllegalStateException(
                    "cliente inativo nao pode ser editado; reative antes: " + id);
        }
        this.nome = exigirNome(nome);
        this.contato = normalizarContato(contato);
    }

    void inativar() {
        this.ativo = false;
    }

    void reativar() {
        this.ativo = true;
    }

    private static String exigirNome(String nome) {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("nome do cliente nao pode ser vazio");
        }
        return nome.trim();
    }

    /** D19a — sem contato tem uma representacao so: ausente. Nunca a string vazia. */
    private static String normalizarContato(String contato) {
        if (contato == null || contato.isBlank()) {
            return null;
        }
        return contato.trim();
    }

    UUID getId() {
        return id;
    }

    /** Existe para o teste de isolamento poder afirmar de que conta a linha e. */
    ContaId getContaId() {
        return ContaId.de(contaId);
    }

    boolean isAtivo() {
        return ativo;
    }

    ClienteService.Cliente paraCliente() {
        return new ClienteService.Cliente(id, nome, contato);
    }
}

/**
 * Repositorio do slice.
 *
 * <p>Toda consulta aqui e filtrada automaticamente por {@code conta_id} pelo {@code @TenantId} —
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Nao escreva {@code WHERE conta_id} a
 * mao, e nao use query nativa: o filtro do Hibernate nao alcanca SQL nativo.
 *
 * <p>Visibilidade de pacote: so o proprio slice consulta cliente. E interface de topo, e nao
 * aninhada em {@link ClienteService}, porque o Spring Data so varre repositorio aninhado quando
 * configurado para isso.
 */
interface ClienteRepository extends JpaRepository<ClienteEntity, UUID> {

    List<ClienteEntity> findByAtivoTrue();
}
