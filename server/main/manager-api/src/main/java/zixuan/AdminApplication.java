package zixuan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import zixuan.common.constant.ProductIdentity;

@SpringBootApplication
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
        System.out.println("http://localhost:8002" + ProductIdentity.ROUTE_PREFIX + "/doc.html");
    }
}
