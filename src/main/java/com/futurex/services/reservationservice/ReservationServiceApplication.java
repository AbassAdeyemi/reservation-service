package com.futurex.services.reservationservice;

import io.r2dbc.spi.ConnectionFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.annotation.Id;
import org.springframework.data.r2dbc.connectionfactory.R2dbcTransactionManager;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@SpringBootApplication
public class ReservationServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ReservationServiceApplication.class, args);
  }

  @Bean
  ReactiveTransactionManager reactiveTransactionManager(ConnectionFactory cf) {
    return new R2dbcTransactionManager(cf);
  }

  @Bean
  TransactionalOperator transactionalOperator(ReactiveTransactionManager transactionManager) {
    return TransactionalOperator.create(transactionManager);
  }

  @Bean
  RouterFunction<ServerResponse> routes(ReservationRepository rr) {
    return route()
        .GET("/reservations", request -> ServerResponse.ok().body(rr.findAll(), Reservation.class))
        .build();
  }
}

@Configuration
@RequiredArgsConstructor
class GreetingsWebSocketConfiguration {

  private final GreetingService gs;

  @Bean
  WebSocketHandler webSocketHandler() {
    return session -> {
      Flux<WebSocketMessage> received =
          session
              .receive()
              .map(WebSocketMessage::getPayloadAsText)
              .map(GreetingRequest::new)
              .flatMap(gs::greet)
              .map(gr -> gr.getMessage())
              .map(session::textMessage);
      return session.send(received);
    };
  }

  @Bean
  SimpleUrlHandlerMapping simpleUrlHandlerMapping(WebSocketHandler webSocketHandler) {
    Map<String, WebSocketHandler> urlMap = new HashMap<>();
    urlMap.put("/ws/greetings", webSocketHandler);
    return new SimpleUrlHandlerMapping(urlMap, 10);
  }

  @Bean
  WebSocketHandlerAdapter webSocketHandlerAdapter() {
    return new WebSocketHandlerAdapter();
  }
}

@Controller
class GreetingService {
  @MessageMapping("greetings")
  Flux<GreetingResponse> greet(GreetingRequest request) {
    return Flux.fromStream(
            Stream.generate(
                () ->
                    new GreetingResponse(
                        "Hello " + request.getName() + " @ " + Instant.now() + "!")))
        .delayElements(Duration.ofSeconds(1));
  }
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class GreetingRequest {
  private String name;
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class GreetingResponse {
  private String message;
}

interface ReservationRepository extends ReactiveCrudRepository<Reservation, Integer> {}

// @RestController
// @RequiredArgsConstructor
// class ReservationController{
//  private final ReservationRepository reservationRepository;
//
//  @GetMapping("/reservations")
//  Flux<Reservation> get() {
//    return this.reservationRepository.findAll();
//  }
// }

@Service
@RequiredArgsConstructor
class ReservationService {
  private final ReservationRepository reservationRepository;
  private final TransactionalOperator transactionalOperator;

  Flux<Reservation> saveAll(String... names) {
    return this.transactionalOperator.transactional(
        Flux.fromArray(names)
            .map(Reservation::new)
            .flatMap(r -> this.reservationRepository.save(r))
            .doOnNext(
                r ->
                    Assert.isTrue(
                        isValid(r), "The reservation name must start with capital letter")));
  }

  private boolean isValid(Reservation r) {
    return Character.isUpperCase(r.getName().charAt(0));
  }
}

@Component
@RequiredArgsConstructor
@Log4j2
class SimpleDataInitializer {

  private final ReservationRepository reservationRepository;
  private final ReservationService reservationService;

  @EventListener(ApplicationReadyEvent.class)
  public void ready() {

    Flux<Reservation> saved =
        reservationService.saveAll("Josh", "Mark", "Steve", "Barry", "Vlad", "Gibbs");

    this.reservationRepository
        .deleteAll()
        .thenMany(saved)
        .thenMany(this.reservationRepository.findAll())
        .subscribe(log::info);
  }
}

@Data
@AllArgsConstructor
@RequiredArgsConstructor
class Reservation {
  @Id private Integer id;
  private String name;

  Reservation(String name) {
    this.name = name;
  }
}
