package com.company.trino;

import com.company.trino.config.TrinoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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
}
