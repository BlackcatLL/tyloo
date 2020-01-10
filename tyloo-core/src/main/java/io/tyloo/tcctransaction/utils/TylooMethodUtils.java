package io.tyloo.tcctransaction.utils;

import io.tyloo.api.TylooContext;
import io.tyloo.tcctransaction.common.Role;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import io.tyloo.api.Tyloo;
import io.tyloo.api.Propagation;

import java.lang.reflect.Method;

/*
 *
 * 注解方法工具类
 *
 * @Author:Zh1Cheung 945503088@qq.com
 * @Date: 19:50 2019/12/4
 *
 */
public class TylooMethodUtils {
    /**
     * 获得带 @Tyloo 注解的方法
     *
     * @param pjp 切面点
     * @return 方法
     */
    public static Method getTylooMethod(ProceedingJoinPoint pjp) {
        return getMethod(pjp);
    }

    public static Method getMethod(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) (pjp.getSignature())).getMethod();

        if (method.getAnnotation(Tyloo.class) == null) {
            try {
                method = pjp.getTarget().getClass().getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
        return method;
    }

    /**
     * 计算方法类型
     *
     * @param propagation         传播级别
     * @param isTransactionActive 是否事务开启
     * @param tylooContext  事务上下文
     * @return 方法类型
     */
    public static Role calculateMethodType(Propagation propagation, boolean isTransactionActive, TylooContext tylooContext) {
        // Propagation.REQUIRED：支持当前事务，当前没有事务，就新建一个事务。
        return getMethodRole(propagation, isTransactionActive, tylooContext);
    }

    public static Role getMethodRole(Propagation propagation, boolean isTransactionActive, TylooContext tylooContext) {
        if ((propagation.equals(Propagation.REQUIRED) && !isTransactionActive && tylooContext == null) ||
                propagation.equals(Propagation.REQUIRES_NEW)) {
            // Propagation.REQUIRES_NEW：新建事务，如果当前存在事务，把当前事务挂起。
            return Role.ROOT;
        } else if ((propagation.equals(Propagation.REQUIRED) || propagation.equals(Propagation.MANDATORY)) && !isTransactionActive && tylooContext != null) {
            // Propagation.REQUIRED：支持当前事务
            return Role.PROVIDER;
        } else {
            // Propagation.MANDATORY：支持当前事务
            return Role.NORMAL;
        }
    }

    public static int getTransactionContextParamPosition(Class<?>[] parameterTypes) {

        int position = -1;

        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i].equals(TylooContext.class)) {
                position = i;
                break;
            }
        }
        return position;
    }
}
