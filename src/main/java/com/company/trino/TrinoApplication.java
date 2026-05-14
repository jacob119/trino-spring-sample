package com.company.trino;

import com.company.trino.config.TrinoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

// DataSource 자동구성 제외 — 커넥션 풀은 TrinoConnectionPool이 직접 관리
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(TrinoProperties.class)
public class TrinoApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrinoApplication.class, args);
    }

    // 유휴 풀 정리 스케줄러에 명시적 스레드 이름을 부여하여 모니터링을 쉽게 한다.
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("trino-pool-cleaner-");
        scheduler.initialize();
        return scheduler;
    }

    /**
     * 비동기 쿼리 실행용 스레드 풀.
     *
     * 코어 = 맥스로 설정하여 스레드 수를 예측 가능하게 유지한다.
     * 큐가 maxQueueSize를 초과하면 CallerRunsPolicy로 HTTP 스레드가 직접 실행하여
     * 백프레셔(back-pressure) 역할을 한다.
     */
    @Bean(name = "queryExecutor")
    public Executor queryExecutor(TrinoProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getAsync().getWorkerThreads());
        executor.setMaxPoolSize(props.getAsync().getWorkerThreads());
        executor.setQueueCapacity(props.getAsync().getMaxQueueSize());
        executor.setThreadNamePrefix("trino-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
