package br.com.caixasimples.cadastro.internal;

import br.com.caixasimples.cadastro.PendenciasDoCliente;
import br.com.caixasimples.shared.ClienteRemovido;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.RegistroDeRemocoes;
import br.com.caixasimples.shared.RegistroDeRemocoes.Tipo;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro de cliente (RF03), edição (RF04) e inativação (RF05).
 *
 * <p><strong>Este arquivo é o slice inteiro</strong>: caso de uso, entidade e repositório moram
 * aqui, e só o caso de uso é público. Não há {@code domain/} nem mapper para cliente de propósito,
 * porque ele não tem invariante nenhuma a proteger, e dar estrutura de agregado a uma entidade
 * assim seria cerimônia. É o mesmo julgamento já aplicado a {@code Conta} e {@code Usuario}.
 *
 * <p>Mora em {@code internal} porque carrega o {@code @Entity}, e JPA vive em {@code internal}. A
 * camada {@code web} nasce depois no mesmo módulo e alcança esta classe sem violar fronteira;
 * outro módulo não alcança, já que pelo Modulith todo subpacote é interno ao módulo.
 *
 * <p>Não existe busca por nome nem vínculo com venda aqui: a busca durante a venda nasce com o
 * módulo de vendas, e a venda referencia o cliente apenas por UUID.
 *
 * <p><strong>Os dois perfis cadastram e editam.</strong> Ao contrário do cadastro de produto, que é do
 * administrador, o cliente costuma ser cadastrado no balcão, na hora da venda, por quem está
 * atendendo. A remoção de dados pessoais exige ADMIN.
 */
@Service
public class ClienteService {

    private final ClienteRepository clientes;
    private final PendenciasDoCliente pendencias;
    private final RegistroDeRemocoes registroDeRemocoes;
    private final ApplicationEventPublisher eventos;

    ClienteService(ClienteRepository clientes, PendenciasDoCliente pendencias,
            RegistroDeRemocoes registroDeRemocoes, ApplicationEventPublisher eventos) {
        this.clientes = clientes;
        this.pendencias = pendencias;
        this.registroDeRemocoes = registroDeRemocoes;
        this.eventos = eventos;
    }

    /**
     * Cadastro de cliente novo (RF03).
     *
     * @return o id do cliente criado, gerado na aplicação e nunca pelo banco (RNF01, RNF03)
     */
    @Transactional
    public UUID cadastrar(DadosDoCliente dados) {
        return cadastrar(UUID.randomUUID(), dados, Instant.now());
    }

    /**
     * O mesmo cadastro, com o id e o instante que o dispositivo gravou ao cadastrar o cliente sem
     * rede (RNF01). Um id que já existe é recusado pela chave primária, porque a linha nova é
     * inserida e nunca mesclada sobre outra.
     */
    @Transactional
    public UUID cadastrar(UUID id, DadosDoCliente dados, Instant criadoEm) {
        Objects.requireNonNull(dados, "dados do cliente nao podem ser nulos");

        return clientes.save(new ClienteEntity(id, dados.nome(), dados.contato(), criadoEm))
                .getId();
    }

    /**
     * Edição do cadastro (RF04). Substitui os dois campos pelo que veio em {@code dados}.
     *
     * @throws ClienteNaoEncontradoException se o id não existe nesta conta
     * @throws IllegalStateException         se o cliente já foi inativado
     */
    @Transactional
    public void editar(UUID id, DadosDoCliente dados) {
        Objects.requireNonNull(dados, "dados do cliente nao podem ser nulos");

        ClienteEntity linha = buscar(id);
        linha.alterar(dados.nome(), dados.contato());
        clientes.save(linha);
    }

    /**
     * Inativação (RF05): soft delete, nunca {@code DELETE}. O cliente some da listagem e o registro
     * fica, para a venda antiga não perder a referência.
     *
     * <p>É idempotente: inativar de novo o que já está inativo não estoura.
     *
     * @throws ClienteNaoEncontradoException se o id não existe nesta conta
     */
    @Transactional
    public void inativar(UUID id) {
        ClienteEntity linha = buscar(id);
        linha.inativar();
        clientes.save(linha);
    }

    /**
     * Traz de volta um cliente inativado. Idempotente pelo mesmo motivo da inativação.
     *
     * <p>Existe aqui e não existe em produto porque lá a reativação esbarraria no índice único de
     * {@code codigo} entre os ativos, que ela poderia violar. A tabela {@code cliente} não tem
     * índice único nenhum, então a operação não esbarra em nada.
     *
     * @throws ClienteNaoEncontradoException se o id não existe nesta conta
     */
    @Transactional
    public void reativar(UUID id) {
        ClienteEntity linha = buscar(id);
        linha.reativar();
        clientes.save(linha);
    }

    /** A remoção preserva o id da Venda, mas retira a identidade do Cliente e suas cópias. */
    @Transactional
    public void remover(UUID id) {
        UsuarioContext.exigirAdmin();
        Objects.requireNonNull(id, "id do cliente nao pode ser nulo");
        ClienteEntity linha = clientes.buscarParaRemocao(id)
                .orElseThrow(() -> new ClienteNaoEncontradoException(id));
        if (linha.isRemovido()) {
            return;
        }
        Money saldo = pendencias.saldoDevedor(id);
        if (!saldo.equals(Money.ZERO)) {
            throw new IllegalStateException("cliente com saldo devedor de " + saldo
                    + " nao pode ser removido");
        }
        if (pendencias.temComandaAberta(id)) {
            throw new IllegalStateException("cliente vinculado a comanda aberta nao pode ser removido");
        }
        Instant instante = Instant.now();
        registroDeRemocoes.registrar(Tipo.CLIENTE, id, instante,
                UsuarioContext.exigirAtual().usuarioId());
        linha.remover(instante);
        clientes.save(linha);
        eventos.publishEvent(new ClienteRemovido(TenantContext.exigirAtual(), id));
    }

    /** Clientes da conta. Cliente inativado não aparece aqui, que é o efeito visível do RF05. */
    @Transactional(readOnly = true)
    public List<Cliente> listarAtivos() {
        return clientes.findByAtivoTrueAndRemovidoEmIsNull().stream()
                .map(ClienteEntity::paraCliente).toList();
    }

    @Transactional(readOnly = true)
    public List<ClienteComVersao> listarAtivosComVersao() {
        return clientes.findByAtivoTrueAndRemovidoEmIsNull().stream()
                .map(ClienteEntity::paraClienteComVersao).toList();
    }

    /** A tela mostra os inativos em separado para permitir reativar sem expor exclusão física. */
    @Transactional(readOnly = true)
    public List<Cliente> listarInativos() {
        return clientes.findByAtivoFalseAndRemovidoEmIsNull().stream()
                .map(ClienteEntity::paraCliente).toList();
    }

    @Transactional(readOnly = true)
    public List<ClienteComVersao> listarInativosComVersao() {
        return clientes.findByAtivoFalseAndRemovidoEmIsNull().stream()
                .map(ClienteEntity::paraClienteComVersao).toList();
    }

    /** O vínculo de uma Venda nova exige Cliente desta Conta e ainda ativo. */
    @Transactional
    public boolean consultarSeAtivo(UUID id) {
        return clientes.buscarParaVinculo(id)
                .orElseThrow(() -> new ClienteNaoEncontradoException(id)).isAtivo();
    }

    /** Histórico de fiado também pode consultar o nome de um Cliente inativo. */
    @Transactional(readOnly = true)
    public Cliente consultar(UUID id) {
        return buscar(id).paraCliente();
    }

    private ClienteEntity buscar(UUID id) {
        Objects.requireNonNull(id, "id do cliente nao pode ser nulo");
        return clientes.findById(id).orElseThrow(() -> new ClienteNaoEncontradoException(id));
    }

    /**
     * Os campos de um cliente, na ordem em que uma tela de cadastro os apresenta.
     *
     * <p>Record em vez de dois parâmetros soltos porque os dois são {@code String}: trocar um pelo
     * outro na chamada compilaria em silêncio.
     *
     * @param nome    obrigatório
     * @param contato opcional, telefone ou e-mail; em branco vira ausente
     */
    public record DadosDoCliente(String nome, String contato) {
    }

    /**
     * O que a listagem devolve.
     *
     * <p>Existe para a entidade continuar em visibilidade de pacote: quem chama o caso de uso
     * recebe um valor, não a linha do banco.
     */
    public record Cliente(UUID id, String nome, String contato) {
    }

    public record ClienteComVersao(UUID id, String nome, String contato, long versao) {
    }

    /**
     * Não existe cliente com esse id <strong>nesta conta</strong>.
     *
     * <p>Como em {@code ProdutoNaoEncontradoException}, um id de outra conta é indistinguível de um
     * id que nunca existiu: o filtro de {@code @TenantId} faz a linha não voltar do banco (RNF05).
     */
    public static class ClienteNaoEncontradoException extends RuntimeException {

        public ClienteNaoEncontradoException(UUID id) {
            super("cliente nao encontrado nesta conta: " + id);
        }
    }
}

/**
 * Linha da tabela {@code cliente}, com a regra dentro, no mesmo molde de {@code Usuario}.
 *
 * <p>Visibilidade de pacote de propósito: quem está fora do slice fala com {@link ClienteService},
 * nunca com a linha.
 *
 * <p>{@code contaId} é preenchido pelo Hibernate a partir do
 * {@code CurrentTenantIdentifierResolver} e filtra toda consulta automaticamente
 * ({@link TenantId}). Não há construtor nem setter que o receba, porque {@code contaId} nunca vem
 * de fora da aplicação (RNF05).
 */
@Entity
@Table(name = "cliente")
class ClienteEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Version
    @Column(nullable = false)
    private Long versao;

    @Column(nullable = false)
    private String nome;

    /** Opcional: telefone ou e-mail. Em branco é gravado como ausente, nunca vazio. */
    private String contato;

    @Column(name = "removido_em")
    private Instant removidoEm;

    /** Soft delete (RF05): preserva a referência das vendas já registradas para este cliente. */
    @Column(nullable = false)
    private boolean ativo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected ClienteEntity() {
        // exigido pelo JPA
    }

    ClienteEntity(UUID id, String nome, String contato, Instant criadoEm) {
        this.id = Objects.requireNonNull(id, "id do cliente nao pode ser nulo");
        this.nome = exigirNome(nome);
        this.contato = normalizarContato(contato);
        this.ativo = true;
        this.criadoEm = Objects.requireNonNull(criadoEm, "criadoEm nao pode ser nulo");
    }

    /**
     * Edição (RF04).
     *
     * <p>Recusa cliente inativo: sem reativar antes, a alteração mudaria em silêncio um registro
     * que não aparece em listagem nenhuma.
     */
    void alterar(String nome, String contato) {
        exigirNaoRemovido();
        if (!ativo) {
            throw new IllegalStateException(
                    "cliente inativo nao pode ser editado; reative antes: " + id);
        }
        this.nome = exigirNome(nome);
        this.contato = normalizarContato(contato);
    }

    void inativar() {
        exigirNaoRemovido();
        this.ativo = false;
    }

    void reativar() {
        exigirNaoRemovido();
        this.ativo = true;
    }

    void remover(Instant instante) {
        exigirNaoRemovido();
        this.nome = "Cliente removido";
        this.contato = null;
        this.ativo = false;
        this.removidoEm = Objects.requireNonNull(instante, "instante nao pode ser nulo");
    }

    boolean isRemovido() {
        return removidoEm != null;
    }

    private void exigirNaoRemovido() {
        if (isRemovido()) {
            throw new IllegalStateException("cliente removido nao pode ser alterado");
        }
    }

    private static String exigirNome(String nome) {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("nome do cliente nao pode ser vazio");
        }
        return nome.trim();
    }

    /** Sem contato tem uma representação só: ausente. Nunca a string vazia. */
    private static String normalizarContato(String contato) {
        if (contato == null || contato.isBlank()) {
            return null;
        }
        return contato.trim();
    }

    UUID getId() {
        return id;
    }

    /** Existe para o teste de isolamento poder afirmar de que conta a linha é. */
    ContaId getContaId() {
        return ContaId.de(contaId);
    }

    boolean isAtivo() {
        return ativo;
    }

    /** A revisão que o dispositivo guarda como a versão lida depois de um gesto sincronizado. */
    Long getVersao() {
        return versao;
    }

    ClienteService.Cliente paraCliente() {
        return new ClienteService.Cliente(id, nome, contato);
    }

    ClienteService.ClienteComVersao paraClienteComVersao() {
        return new ClienteService.ClienteComVersao(id, nome, contato, versao);
    }
}

/**
 * Repositório do slice.
 *
 * <p>Toda consulta aqui é filtrada automaticamente por {@code conta_id} pelo {@code @TenantId},
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Não escreva {@code WHERE conta_id} à
 * mão, e não use query nativa: o filtro do Hibernate não alcança SQL nativo.
 *
 * <p>Visibilidade de pacote, porque só o próprio slice consulta cliente. É interface de topo, e não
 * aninhada em {@link ClienteService}, porque o Spring Data só varre repositório aninhado quando
 * configurado para isso.
 */
interface ClienteRepository extends JpaRepository<ClienteEntity, UUID> {

    List<ClienteEntity> findByAtivoTrueAndRemovidoEmIsNull();

    List<ClienteEntity> findByAtivoFalseAndRemovidoEmIsNull();

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from ClienteEntity c where c.id = :id")
    java.util.Optional<ClienteEntity> buscarParaVinculo(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ClienteEntity c where c.id = :id")
    java.util.Optional<ClienteEntity> buscarParaRemocao(@Param("id") UUID id);
}
