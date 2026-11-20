# Cliente Java

Coleta download e upload por interface usando Java 25, OSHI 7.7.2, records e JDBC. O Maven Shade inclui as dependências no JAR.

A instalação é feita pelo script-instalacao-cliente.sh do repositório [script-instalacao-pi](https://github.com/AeroGuard-PI/script-instalacao-pi). Ele baixa este repositório e script-python separadamente e constrói as imagens Docker. A empresa vem do login e o MAC é obtido automaticamente. O Java recebe a configuração preparada pelo instalador.

Para compilar no ambiente de desenvolvimento:

```bash
mvn clean package
```

O resultado é `target/aeroguard-java.jar`. O Dockerfile faz essa compilação usando JDK 25 e executa o JAR em uma JRE 25.

A taxa usa `(delta de bytes × 8) / segundos decorridos / 1.000.000`, em Mbps. A primeira leitura prepara a referência. Reset de contador ou troca da interface descarta a comparação anterior.

`interfaceNome`, como ens5 ou eth0, é o nome local da interface. O MAC identifica a EC2; a coleta inclui as interfaces disponíveis e não se limita àquela usada no cadastro.

Uma thread agenda os ciclos a cada cinco segundos. Records e listas imutáveis transportam as leituras, e o histórico é protegido. As capturas são gravadas em lote e transação. A configuração mais recente com disponibilidade 0 impede novas inserções sem ser reativada pelo agente.

As duas FKs de configuração em captura recebem o ID da configuração consultada. Uma falha na leitura ou no banco é registrada e os ciclos seguintes continuam. Não há reenvio de amostras perdidas.

## Slack

Defina SLACK_BOT_TOKEN em coletor.env. O instalador repassa o arquivo ao contêiner; em execução local, as variáveis precisam estar definidas no processo ou na IDE. O envio usa o SDK oficial do Slack. As mensagens agrupam download e upload por interface e mostram os horários de Brasília.

Ativação, canal, modo, nível mínimo e cooldown vêm de configuracao_slack, pela empresa do CNPJ configurado. Sem token ou configuração válida, não há envio. O bot precisa estar no canal e ter a permissão chat:write. Prefira o ID do canal.

- TUDO: envia as capturas de rede persistidas, agrupadas em uma mensagem por ciclo disponível.
- CONFIGURADO: envia eventos de Rede já registrados em historico_alerta, vinculados à mesma empresa e configuração da captura. Aplica nível mínimo e cooldown em minutos; zero desativa lembretes. Um evento já enviado não é repetido.

A geração de alertas de rede está adiada. O Java não aplica os limites percentuais de CPU, memória ou disco aos valores em Mbps. Sem eventos válidos no histórico, CONFIGURADO permanece sem mensagens.

A consulta e o envio ocorrem após a gravação, em uma tarefa separada, sem acumular solicitações enquanto há uma em andamento. Falhas do Slack não interrompem a coleta. O cliente respeita Retry-After em HTTP 429 e confirma o envio somente quando a API retorna sucesso.

O esquema do banco está em [Site/src/database/AeroGuard.sql](https://github.com/AeroGuard-PI/Site/blob/main/src/database/AeroGuard.sql).
