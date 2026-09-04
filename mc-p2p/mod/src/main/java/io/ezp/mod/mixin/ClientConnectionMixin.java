package io.ezp.mod.mixin;

import io.ezp.mod.net.SocksManager;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.net.InetSocketAddress;

/**
 * 修改 Minecraft 发往目标服务器的连接目标。
 *
 * 1.21.4 玩家侧存在两个静态 connect(InetSocketAddress, boolean, ...) 重载，
 * 这里对两者均做地址改写：若目标是已托管的房主虚拟 IP，改写为本地转发端口
 * （由本机 {@link LocalProxyForwarder} 经内置 SOCKS5 代理转发）；否则原样返回。
 *
 * 说明：无对应路由时一律直接把参数原样返回，因此本 Mixin 不影响任何普通连接，
 * 最坏情况只是 P2P 路由不生效，绝不会导致崩溃。
 */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {

    @ModifyVariable(
            method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;",
            at = @At("HEAD"),
            argsOnly = true,
            index = 0
    )
    private static InetSocketAddress ezp$rewriteHost(InetSocketAddress addr) {
        return SocksManager.maybeRewrite(addr);
    }

    @ModifyVariable(
            method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/util/profiler/MultiValueDebugSampleLogImpl;)Lnet/minecraft/network/ClientConnection;",
            at = @At("HEAD"),
            argsOnly = true,
            index = 0
    )
    private static InetSocketAddress ezp$rewriteHost2(InetSocketAddress addr) {
        return SocksManager.maybeRewrite(addr);
    }
}