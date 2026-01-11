package com.tradingBot.model;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class OrderStatusResponse {
    private String status;
    private String orderId;
    private BigDecimal fillPrice;
    private Integer fillQuantity;
    private LocalDateTime fillTime;
    private String symbol;
    private String side;
    private String type;
    private BigDecimal originalPrice;
    private Integer originalQuantity;

    public OrderStatusResponse() {}

    public OrderStatusResponse(String status, String orderId) {
        this.status = status;
        this.orderId = orderId;
    }
}
