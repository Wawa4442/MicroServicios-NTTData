package tacos.coupon;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * External coupon catalog (TC-15). Commercial values live in configuration,
 * so promotions can change without a rebuild. The default is an empty catalog:
 * without a configured coupon, quoting an unknown code is a defined answer.
 */
@Component
@ConfigurationProperties(prefix = "tacos.coupons")
@Data
public class CouponProperties {

  private List<CouponDefinition> coupons = new ArrayList<>();

}