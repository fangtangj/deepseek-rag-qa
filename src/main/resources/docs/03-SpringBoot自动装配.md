# 第 2 讲 Spring Boot 自动装配与启动流程

## 一、什么是自动装配

在没有 Spring Boot 之前，用 Spring 搭一个 Web 项目要写大量 XML 或 Java 配置：配数据源、配事务管理器、配 DispatcherServlet、配视图解析器……繁琐且容易出错。

**自动装配（Auto-configuration）** 是 Spring Boot 的核心特性：框架根据你引入的依赖和已有的配置，**自动推断并注册**你需要的 Bean。比如你引入了 `spring-boot-starter-webmvc` 并且没自己定义 DispatcherServlet，Spring Boot 就自动帮你配好一个可用的 Web 环境。

一句话理解：自动装配 = **约定优于配置 + 条件化装配**。它把"大家都会这么配"的部分预先写好，只在满足条件时才生效。

## 二、@SpringBootApplication 是一个组合注解

每个 Spring Boot 项目的启动类上都有 `@SpringBootApplication`，它本质是三个注解的组合：

```java
@SpringBootConfiguration   // 本质是 @Configuration，声明这是一个配置类
@EnableAutoConfiguration   // 开启自动装配，核心
@ComponentScan             // 扫描当前包及子包下的 @Component / @Service / @Controller 等
```

其中 `@EnableAutoConfiguration` 才是自动装配的开关。

## 三、自动装配的加载机制

### 3.1 老机制（Spring Boot 2.7 之前）

`@EnableAutoConfiguration` 内部通过 `@Import(AutoConfigurationImportSelector.class)` 引入选择器。这个选择器会读取所有依赖 jar 包中 `META-INF/spring.factories` 文件里 `EnableAutoConfiguration` 键对应的配置类全限定名列表，把它们作为候选自动配置类加载进来。

### 3.2 新机制（Spring Boot 2.7 及以后）

从 Spring Boot 2.7 起，自动配置类的注册方式改为读取 **`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`** 文件，每行一个配置类全限定名，不再用 `spring.factories`。

**为什么改**：`spring.factories` 是 Spring 通用的扩展机制，里面混着各种键（监听器、初始化器、自动配置等），职责不清且加载性能一般。拆出独立文件后，自动配置的加载更快、更清晰。

**面试提醒**：回答这个问题时，先说结论（2.7 是分界线），再补充两个文件路径，能体现你是真的看过源码而不是背的。

### 3.3 候选类不是全部生效

关键点：**被列出来的自动配置类只是"候选"，不一定会生效**。是否生效由每个配置类上的**条件注解**决定。这就是"条件化装配"。

## 四、条件注解（自动装配的灵魂）

Spring Boot 提供了一组 `@Conditional` 派生注解，用来判断"满不满足条件，满不满足才装配"：

| 注解 | 生效条件 |
|---|---|
| `@ConditionalOnClass` | 类路径上存在指定类才生效。例如只有引入了 Redis 客户端，Redis 的自动配置才生效 |
| `@ConditionalOnMissingClass` | 类路径上不存在指定类时生效 |
| `@ConditionalOnBean` | 容器中已存在指定 Bean 时生效 |
| `@ConditionalOnMissingBean` | 容器中**不存在**指定 Bean 时才生效。**这是"用户配置优先于自动配置"的实现基础** |
| `@ConditionalOnProperty` | 配置文件中指定属性满足条件时生效，如 `@ConditionalOnProperty(name="x", havingValue="true")` |
| `@ConditionalOnWebApplication` | 当前是 Web 应用时生效 |
| `@ConditionalOnResource` | 指定资源文件存在时生效 |

**核心机制**：`@ConditionalOnMissingBean` 保证了——如果你自己定义了一个 Bean，Spring Boot 就放弃它自己的默认实现。这正是"约定优于配置"能被打破的原因。

## 五、自动配置的执行顺序

自动配置类之间也有顺序要求，通过 `@AutoConfigureBefore`、`@AutoConfigureAfter`、`@AutoConfigureOrder` 控制。

一个典型例子：数据源自动配置必须在 MyBatis / JPA 自动配置之前完成，否则 ORM 框架拿不到 DataSource。Spring Boot 在内部维护了这些依赖关系。

## 六、如何排除或覆盖自动配置

### 6.1 排除某个自动配置

方式一，在注解上排除：

```java
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
```

方式二，在配置文件里排除：

```yaml
spring:
  autoconfigure:
    exclude: org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
```

### 6.2 覆盖默认 Bean

最常用的方式是自己定义一个同类型的 Bean。由于 `@ConditionalOnMissingBean` 的存在，你的 Bean 会生效，自动配置的默认 Bean 会退让。

### 6.3 用配置文件改参数

不改代码，直接在 `application.yml` 里覆盖自动配置类绑定的属性，例如：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/course
```

这些属性之所以能生效，是因为自动配置类上标了 `@ConfigurationProperties`，把配置文件的键绑定到了 Bean 的字段上。

## 七、动手看一个真实的自动配置类

以数据源为例，简化后的结构大致是：

```java
@AutoConfiguration
@ConditionalOnClass({ DataSource.class, EmbeddedDatabaseType.class })
@ConditionalOnMissingBean(type = "io.r2dbc.spi.ConnectionFactory")
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DataSource dataSource(DataSourceProperties properties) {
        // 根据 properties 创建数据源
    }
}
```

读这段代码要抓住三点：

1. `@ConditionalOnClass`：类路径有 DataSource 才生效。
2. `@ConditionalOnMissingBean`：你自己配了 DataSource 就不生效。
3. `@EnableConfigurationProperties`：把 `spring.datasource.*` 的配置绑定到 `DataSourceProperties`。

## 八、如何自己写一个 Starter

理解了自动装配，就能写自己的 Starter（面试加分项）：

1. 建立一个 `xxx-spring-boot-starter` 模块。
2. 写一个自动配置类，用 `@AutoConfiguration` + 条件注解。
3. 在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 中登记该类。
4. 用 `@ConfigurationProperties` 定义可配置属性，并在自动配置类上用 `@EnableConfigurationProperties` 启用。
5. 打包发布，其他项目引入依赖即生效。

## 九、本讲小结与面试高频问题

**必须能回答的问题**：

1. `@SpringBootApplication` 由哪几个注解组成？
2. 自动配置类的加载入口是什么？2.7 前后有什么区别？
3. `@ConditionalOnMissingBean` 的作用是什么？它解决了什么问题？
4. 如何排除一个自动配置类？（说出至少两种方式）
5. 为什么你自己定义的 Bean 能覆盖自动配置的默认 Bean？
6. 写一个自定义 Starter 的步骤是什么？

**核心理解**：自动装配不是"魔法"，它是两句话——**"把候选配置类列出来"（imports 文件）+ "用条件注解决定谁生效"（@Conditional 系列）**。把这两句说清楚，这道题就答对了。

**课后作业**：写一个自定义 Starter，功能是当类路径存在某工具类时，自动注册一个打印"hello"的 Bean；并通过加 `@ConditionalOnProperty` 让它可以通过配置开关关闭。验证三种情况（有依赖无配置 / 有依赖有配置 / 无依赖）下的行为差异。
