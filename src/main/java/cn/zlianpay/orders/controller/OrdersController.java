package cn.zlianpay.orders.controller;

import cn.zlianpay.carmi.entity.Cards;
import cn.zlianpay.carmi.service.CardsService;
import cn.zlianpay.common.core.enmu.Alipay;
import cn.zlianpay.common.core.enmu.Paypal;
import cn.zlianpay.common.core.enmu.QQPay;
import cn.zlianpay.common.core.enmu.Wxpay;
import cn.zlianpay.common.core.utils.DateUtil;
import cn.zlianpay.common.core.utils.FormCheckUtil;
import cn.zlianpay.common.core.web.*;
import cn.zlianpay.reception.dto.SearchDTO;
import cn.zlianpay.settings.entity.ShopSettings;
import cn.zlianpay.settings.service.ShopSettingsService;
import cn.zlianpay.website.entity.Website;
import cn.zlianpay.website.service.WebsiteService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cn.zlianpay.common.core.utils.RequestParamsUtil;
import cn.zlianpay.common.core.annotation.OperLog;
import cn.zlianpay.common.system.service.EmailService;
import cn.zlianpay.orders.entity.Orders;
import cn.zlianpay.orders.service.OrdersService;
import cn.zlianpay.orders.vo.OrdersVo;
import cn.zlianpay.products.entity.Products;
import cn.zlianpay.products.service.ProductsService;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 订单表管理
 * Created by Panyoujie on 2021-03-29 16:24:28
 */
@Controller
@Transactional
@RequestMapping("/orders/orders")
public class OrdersController extends BaseController {

    @Autowired
    private OrdersService ordersService;

    @Autowired
    private ProductsService productsService;

    @Autowired
    private CardsService cardsService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private WebsiteService websiteService;

    @Autowired
    private ShopSettingsService shopSettingsService;

    @RequiresPermissions("orders:orders:view")
    @RequestMapping()
    public String view() {
        return "orders/orders.html";
    }

    /**
     * 分页查询订单表
     */
    @RequiresPermissions("orders:orders:list")
    @OperLog(value = "订单表管理", desc = "分页查询")
    @ResponseBody
    @RequestMapping("/page")
    public JsonResult page(HttpServletRequest request) {
        PageParam<Orders> pageParam = new PageParam<>(request);
        pageParam.setDefaultOrder(null, new String[]{"create_time"});
        PageResult<Orders> ordersPageResult = ordersService.listPage(pageParam);
        List<OrdersVo> ordersVoList = ordersPageResult.getData().stream().map((orders) -> {
            OrdersVo ordersVo = new OrdersVo();
            BeanUtils.copyProperties(orders, ordersVo);

            Products products = productsService.getById(orders.getProductId());
            ordersVo.setProductName(products.getName());

            List<String> cardsList = new ArrayList<>();
            if (!StringUtils.isEmpty(orders.getCardsInfo())) {
                String[] cardsInfo = orders.getCardsInfo().split(",");
                for (String cardInfo : cardsInfo) {
                    StringBuilder cardInfoText = new StringBuilder();
                    if (products.getShipType() == 0) {
                        if (cardInfo.contains(" ")) {
                            String[] split = cardInfo.split(" ");
                            cardInfoText.append("卡号：").append(split[0]).append(" ").append("卡密：").append(split[1]).append("\n");
                        } else {
                            cardInfoText.append("卡密：").append(cardInfo).append("\n");
                        }
                        cardsList.add(cardInfoText.toString());
                    } else {
                        cardInfoText.append(cardInfo);
                        cardsList.add(cardInfoText.toString());
                    }
                }
            }

            ordersVo.setCardInfo(cardsList);

            if (orders.getPayTime() != null) {
                ordersVo.setPayTime(DateUtil.getSubDateMiao(orders.getPayTime()));
            } else {
                ordersVo.setPayTime(null);
            }

            ordersVo.setMoney(orders.getMoney().toString());

            // 发货模式
            ordersVo.setShipType(products.getShipType());

            if (!StringUtils.isEmpty(orders.getAttachInfo())) {
                String attachInfo = orders.getAttachInfo();
                boolean contains = attachInfo.contains(",");
                List<Map<String, String>> mapList = new ArrayList<>();
                if (contains) {
                    String[] split = attachInfo.split(",");
                    for (String s : split) {
                        String[] split1 = s.split("=");
                        Map<String, String> map = new HashMap<>();
                        map.put("name", split1[0]);
                        if (split1.length == 2) {
                            map.put("value", split1[1]);
                        } else {
                            map.put("value", "");
                        }
                        mapList.add(map);
                    }
                    ordersVo.setAttachInfoList(mapList);
                } else {
                    String[] split1 = attachInfo.split("=");
                    Map<String, String> map = new HashMap<>();
                    map.put("name", split1[0]);
                    if (split1.length == 2) {
                        map.put("value", split1[1]);
                    } else {
                        map.put("value", "");
                    }
                    mapList.add(map);
                    ordersVo.setAttachInfoList(mapList);
                }
            }

            return ordersVo;
        }).collect(Collectors.toList());

        BigDecimal totalAmount = new BigDecimal(0.00);
        for (OrdersVo ordersVo : ordersVoList) {
            if (ordersVo.getStatus() >= 1) {
                totalAmount = totalAmount.add(new BigDecimal(ordersVo.getMoney())).setScale(2, BigDecimal.ROUND_HALF_DOWN);
            }
        }
        Map<String, String> totalRow = new HashMap<>();
        totalRow.put("money", totalAmount.toString());

        return JsonResult.ok("查询成功！").put("totalRow", totalRow).put("count", ordersPageResult.getCount()).setData(ordersVoList);
    }

    /**
     * 分页查询
     */
    @OperLog(value = "管理", desc = "分页查询")
    @ResponseBody
    @RequestMapping("/pageAll")
    public JsonResult pageall(HttpServletRequest request) {
        PageParam<Orders> pageParam = new PageParam<>(request);

        Map parameterMap = RequestParamsUtil.getParameterMap(request);
        String contact = (String) parameterMap.get("contact");
        QueryWrapper<Orders> wrapper = new QueryWrapper<>();
        wrapper.eq("contact", contact)
                .or().eq("email", contact)
                .or().eq("member", contact)
                .or().eq("pay_no", contact)
                .orderByDesc("create_time");

        List<Orders> ordersList = ordersService.page(pageParam, wrapper).getRecords();

        AtomicInteger index = new AtomicInteger(0);
        List<SearchDTO> orderVosList = ordersList.stream().map((orders) -> {

            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss");//设置日期格式
            String date = df.format(orders.getCreateTime());// new Date()为获取当前系统时间，也可使用当前时间戳

            SearchDTO searchDTO = new SearchDTO();
            searchDTO.setId(orders.getId().toString());
            Integer andIncrement = (Integer) index.getAndIncrement();
            searchDTO.setAndIncrement(andIncrement.toString());
            searchDTO.setCreateTime(date);
            searchDTO.setMoney(orders.getMoney().toString());

            if (Alipay.getByValue(orders.getPayType())) {
                searchDTO.setPayType("支付宝");
            } else if (Wxpay.getByValue(orders.getPayType())) {
                searchDTO.setPayType("微信");
            } else if (Paypal.getByValue(orders.getPayType())) {
                searchDTO.setPayType("Paypal");
            } else if (QQPay.getByValue(orders.getPayType())) {
                searchDTO.setPayType("QQ钱包");
            }
            if (orders.getStatus() == 1) {
                searchDTO.setStatus("已支付");
            } else if (orders.getStatus() == 2) {
                searchDTO.setStatus("待发货");
            } else if (orders.getStatus() == 3) {
                searchDTO.setStatus("已发货");
            } else {
                searchDTO.setStatus("未付款");
            }

            searchDTO.setMember(orders.getMember());

            return searchDTO;
        }).collect(Collectors.toList());

        return JsonResult.ok("查询成功！").setData(orderVosList);
    }

    /**
     * 查询全部订单表
     */
    @RequiresPermissions("orders:orders:list")
    @OperLog(value = "订单表管理", desc = "查询全部")
    @ResponseBody
    @RequestMapping("/list")
    public JsonResult list(HttpServletRequest request) {
        PageParam<Orders> pageParam = new PageParam<>(request);
        return JsonResult.ok().setData(ordersService.list(pageParam.getOrderWrapper()));
    }

    /**
     * 根据id查询订单表
     */
    @RequiresPermissions("orders:orders:list")
    @OperLog(value = "订单表管理", desc = "根据id查询")
    @ResponseBody
    @RequestMapping("/get")
    public JsonResult get(Integer id) {
        return JsonResult.ok().setData(ordersService.getById(id));
    }

    /**
     * 添加订单表
     */
    @RequiresPermissions("orders:orders:save")
    @OperLog(value = "订单表管理", desc = "添加", param = false, result = true)
    @ResponseBody
    @RequestMapping("/save")
    public JsonResult save(Orders orders) {
        if (ordersService.save(orders)) {
            return JsonResult.ok("添加成功");
        }
        return JsonResult.error("添加失败");
    }

    /**
     * 修改订单表
     */
    @RequiresPermissions("orders:orders:update")
    @OperLog(value = "订单表管理", desc = "修改", param = false, result = true)
    @ResponseBody
    @RequestMapping("/update")
    public JsonResult update(Orders orders) {
        if (ordersService.updateById(orders)) {
            return JsonResult.ok("修改成功");
        }
        return JsonResult.error("修改失败");
    }

    /**
     * 删除订单表
     */
    @RequiresPermissions("orders:orders:remove")
    @OperLog(value = "订单表管理", desc = "删除", result = true)
    @ResponseBody
    @RequestMapping("/remove")
    public JsonResult remove(Integer id) {
        if (ordersService.removeById(id)) {
            return JsonResult.ok("删除成功");
        }
        return JsonResult.error("删除失败");
    }

    /**
     * 批量添加订单表
     */
    @RequiresPermissions("orders:orders:save")
    @OperLog(value = "订单表管理", desc = "批量添加", param = false, result = true)
    @ResponseBody
    @RequestMapping("/saveBatch")
    public JsonResult saveBatch(@RequestBody List<Orders> list) {
        if (ordersService.saveBatch(list)) {
            return JsonResult.ok("添加成功");
        }
        return JsonResult.error("添加失败");
    }

    /**
     * 批量修改订单表
     */
    @RequiresPermissions("orders:orders:update")
    @OperLog(value = "订单表管理", desc = "批量修改", result = true)
    @ResponseBody
    @RequestMapping("/updateBatch")
    public JsonResult updateBatch(@RequestBody BatchParam<Orders> batchParam) {
        if (batchParam.update(ordersService, "id")) {
            return JsonResult.ok("修改成功");
        }
        return JsonResult.error("修改失败");
    }

    /**
     * 批量删除订单表
     */
    @RequiresPermissions("orders:orders:remove")
    @OperLog(value = "订单表管理", desc = "批量删除", result = true)
    @ResponseBody
    @RequestMapping("/removeBatch")
    public JsonResult removeBatch(@RequestBody List<Integer> ids) {
        if (ordersService.removeByIds(ids)) {
            return JsonResult.ok("删除成功");
        }
        return JsonResult.error("删除失败");
    }

    /**
     * 批量删除订单表
     */
    @RequiresPermissions("orders:orders:remove")
    @OperLog(value = "订单表管理", desc = "批量删除", result = true)
    @ResponseBody
    @RequestMapping("/clearRemove")
    public JsonResult clearRemove() {
        if (ordersService.clearRemove()) {
            return JsonResult.ok("清理未支付的订单成功！");
        }
        return JsonResult.error("没有可以清理的订单！");
    }

    /**
     * 批量删除订单表
     */
    @RequiresPermissions("orders:orders:remove")
    @OperLog(value = "订单表管理", desc = "批量删除", result = true)
    @ResponseBody
    @RequestMapping("/clearAllRemove")
    public JsonResult clearAllRemove() {
        if (ordersService.clearAllRemove()) {
            return JsonResult.ok("清理的订单成功！");
        }
        return JsonResult.error("没有可以清理的订单！");
    }

    /**
     * 删除订单表
     */
    @RequiresPermissions("orders:orders:remove")
    @OperLog(value = "订单表管理", desc = "订单删除", result = true)
    @ResponseBody
    @RequestMapping("/deleteById")
    public JsonResult deleteById(Integer id) {
        if (ordersService.deleteById(id)) {
            return JsonResult.ok("删除订单成功！");
        }
        return JsonResult.error("没有可以删除的订单！");
    }

    /**
     * @param id       商品id
     * @param shipInfo 需要发货的内容
     * @return
     */
    @OperLog(value = "商品列表管理", desc = "手动发货", result = true)
    @RequiresPermissions("orders:orders:update")
    @ResponseBody
    @RequestMapping("/sendShip")
    public JsonResult sendShip(Integer id, String shipInfo) throws MessagingException, IOException {

        /**
         * 查出订单
         */
        Orders orders = ordersService.getById(id);
        Products products = productsService.getById(orders.getProductId()); // 查出对应的商品

        Cards cards = new Cards();
        cards.setCardInfo(shipInfo);
        cards.setCreatedAt(new Date());
        cards.setProductId(products.getId());
        cards.setStatus(1); // 默认已使用
        cards.setNumber(0);
        cards.setSellNumber(1);
        cards.setUpdatedAt(new Date());

        Orders orders1 = new Orders();
        orders1.setId(orders.getId());
        orders1.setStatus(3);
        orders1.setCardsInfo(shipInfo);

        Website website = websiteService.getById(1);
        ShopSettings shopSettings = shopSettingsService.getById(1);

        if (ordersService.updateById(orders1)) {
            cardsService.save(cards);
            /**
             * 邮件通知
             * 后台开启邮件通知，
             * 这里会给下单用户的邮箱发送一条邮件
             */
            if (shopSettings.getIsEmail() == 1) {
                if (FormCheckUtil.isEmail(orders.getEmail())) {
                    try {
                        Map<String, Object> map = new HashMap<>();  // 页面的动态数据
                        map.put("title", website.getWebsiteName());
                        map.put("member", orders.getMember());
                        map.put("date", DateUtil.getDate());
                        map.put("info", shipInfo);
                        emailService.sendHtmlEmail(website.getWebsiteName() + "发货提醒", "email/sendShip.html", map, new String[]{orders.getEmail()});
                    } catch (Exception e) {
                        e.printStackTrace();
                        return JsonResult.error("发货成功、邮箱提醒用户失败、请检查邮箱系统配置。");
                    }
                }
            }
            return JsonResult.ok("手动发货成功！");
        }
        return JsonResult.error("手动发货失败！");
    }

    /**
     * 修改商品状态
     */
    @OperLog(value = "商品列表管理", desc = "商品手动补单", result = true)
    @RequiresPermissions("orders:orders:update")
    @ResponseBody
    @RequestMapping("/status/update")
    public JsonResult updateStates(Integer id, String payNo, Integer productId) {

        Orders member = ordersService.getById(id);
        if (member == null) return JsonResult.error("没有找到相关订单"); // 本地没有这个订单

        boolean empty = StringUtils.isEmpty(member.getCardsInfo());
        if (!empty) return JsonResult.ok("已经支付成功！自动发卡成功，补单失败");

        Products products = productsService.getById(productId);
        if (products == null) return JsonResult.error("该订单的商品找不到！"); // 商品没了

        Website website = websiteService.getById(1);
        ShopSettings shopSettings = shopSettingsService.getById(1);

        if (products.getShipType() == 0) { // 自动发货的商品

            StringBuilder stringBuilder = new StringBuilder(); // 通知信息需要的卡密信息

            if (products.getSellType() == 0) { // 一次性卡密类型
                /**
                 * 卡密信息列表
                 * 通过商品购买数量来获取对应商品的卡密数量
                 */
                List<Cards> card = cardsService.getCard(0, products.getId(), member.getNumber());
                if (card == null) return JsonResult.error("卡密为空！请补充后再试。");

                StringBuilder orderInfo = new StringBuilder(); // 订单关联的卡密信息

                for (Cards cards : card) {
                    orderInfo.append(cards.getCardInfo()).append(","); // 通过StringBuilder 来拼接卡密信息

                    /**
                     * 设置每条被购买的卡密的售出状态
                     */
                    Cards cards1 = new Cards();
                    cards1.setId(cards.getId());
                    cards1.setStatus(1);
                    cards1.setNumber(0);
                    cards1.setSellNumber(1);
                    cards1.setUpdatedAt(new Date());

                    // 设置售出的卡密
                    cardsService.updateById(cards1);

                    if (cards.getCardInfo().contains(" ")) {
                        String[] split = cards.getCardInfo().split(" ");
                        stringBuilder.append("卡号：").append(split[0]).append(" ").append("卡密：").append(split[1]).append("\n");
                    } else {
                        stringBuilder.append("卡密：").append(cards.getCardInfo()).append("\n");
                    }
                }

                // 去除多余尾部的逗号
                String result = orderInfo.deleteCharAt(orderInfo.length() - 1).toString();

                Orders orders = new Orders();
                orders.setId(member.getId());
                orders.setCardsInfo(result);

                // 更新售出卡密
                ordersService.updateById(orders);
            } else if (products.getSellType() == 1) { // 重复销售的卡密
                StringBuilder orderInfo = new StringBuilder(); // 订单关联的卡密信息

                Cards cards = cardsService.getOne(new QueryWrapper<Cards>().eq("product_id", products.getId()).eq("status", 0));
                if (cards == null) return JsonResult.error("卡密为空！请补充后再试。");

                /**
                 * 设置每条被购买的卡密的售出状态
                 */
                Cards cards1 = new Cards();
                cards1.setId(cards.getId());
                cards1.setUpdatedAt(new Date());
                if (cards.getNumber() == 1) { // 还剩下一个卡密
                    cards1.setSellNumber(cards.getSellNumber() + member.getNumber());
                    cards1.setNumber(cards.getNumber() - member.getNumber()); // 减完之后等于0
                    cards1.setStatus(1); // 设置状态为已全部售出
                } else {
                    cards1.setSellNumber(cards.getSellNumber() + member.getNumber());
                    cards1.setNumber(cards.getNumber() - member.getNumber());
                }

                // 设置售出的卡密
                cardsService.updateById(cards1);

                if (cards.getCardInfo().contains(" ")) {
                    String[] split = cards.getCardInfo().split(" ");
                    stringBuilder.append("卡号：").append(split[0]).append(" ").append("卡密：").append(split[1]).append("\n");
                } else {
                    stringBuilder.append("卡密：").append(cards.getCardInfo()).append("\n");
                }

                /**
                 * 看用户购买了多少个卡密
                 * 正常重复的卡密不会购买1个以上
                 * 这里做个以防万一呀（有钱谁不赚）
                 */
                for (int i = 0; i < member.getNumber(); i++) {
                    orderInfo.append(cards.getCardInfo()).append(",");
                }

                Orders orders = new Orders();
                orders.setId(member.getId());
                orders.setCardsInfo(cards.getCardInfo());

                // 更新售出卡密
                ordersService.updateById(orders);
            }

            /**
             * 微信的 wxpush 通知
             * 本通知只针对站长
             * 当用户购买成功后会给您设置的
             * wxpush 微信公众号发送订单购买成功后的通知
             */
            if (shopSettings.getIsWxpusher() == 1) {
                Message message = new Message();
                message.setContent(website.getWebsiteName() + "新订单提醒<br>订单号：<span style='color:red;'>" + member.getMember() + "</span><br>商品名称：<span>" + products.getName() + "</span><br>购买数量：<span>" + member.getNumber() + "</span><br>订单金额：<span>" + member.getMoney() + "</span><br>支付状态：<span style='color:green;'>成功</span><br>");
                message.setContentType(Message.CONTENT_TYPE_HTML);
                message.setUid(shopSettings.getWxpushUid());
                message.setAppToken(shopSettings.getAppToken());
                WxPusher.send(message);
            }

            /**
             * 邮件通知
             * 后台开启邮件通知，
             * 这里会给下单用户的邮箱发送一条邮件
             */
            if (shopSettings.getIsEmail() == 1) {
                if (!StringUtils.isEmpty(member.getEmail())) {
                    if (FormCheckUtil.isEmail(member.getEmail())) {
                        Map<String, Object> map = new HashMap<>();  // 页面的动态数据
                        map.put("title", website.getWebsiteName());
                        map.put("member", member.getMember());
                        map.put("date", DateUtil.getDate());
                        map.put("info", stringBuilder.toString());
                        try {
                            emailService.sendHtmlEmail(website.getWebsiteName() + "发货提醒", "email/sendShip.html", map, new String[]{member.getEmail()});
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } else { // 手动发货商品
            Products products1 = new Products();
            products1.setId(products.getId());
            products1.setInventory(products.getInventory() - 1);
            products1.setSales(products.getSales() + 1);

            /**
             * 微信的 wxpush 通知
             * 本通知只针对站长
             * 当用户购买成功后会给您设置的
             * wxpush 微信公众号发送订单购买成功后的通知
             */
            if (shopSettings.getIsWxpusher() == 1) {
                Message message = new Message();
                message.setContent(website.getWebsiteName() + "新订单提醒<br>订单号：<span style='color:red;'>" + member.getMember() + "</span><br>商品名称：<span>" + products.getName() + "</span><br>订单金额：<span>" + member.getMoney() + "</span><br>支付状态：<span style='color:green;'>成功</span><br>");
                message.setContentType(Message.CONTENT_TYPE_HTML);
                message.setUid(shopSettings.getWxpushUid());
                message.setAppToken(shopSettings.getAppToken());
                WxPusher.send(message);
            }

            /**
             * 邮件通知
             * 后台开启邮件通知，
             * 这里会给下单用户的邮箱发送一条邮件
             */
            if (shopSettings.getIsEmail() == 1) {
                if (FormCheckUtil.isEmail(member.getEmail())) {
                    try {
                        emailService.sendTextEmail("订单提醒", "您的订单号为：" + member.getMember() + " 本商品为手动发货，请耐心等待！", new String[]{member.getEmail()});
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            productsService.updateById(products1);
        }

        /**
         * 更新订单
         */
        Orders orders = new Orders();
        orders.setId(member.getId());

        if (products.getShipType() == 0) {
            orders.setStatus(1); // 设置已售出
        } else {
            orders.setStatus(2); // 手动发货模式 为待处理
        }

        orders.setPayTime(new Date());
        orders.setPayNo(payNo);
        orders.setPrice(member.getPrice());
        orders.setMoney(member.getMoney());

        ordersService.updateById(orders);// 更新售出

        return JsonResult.ok("补单成功！！");
    }

}
