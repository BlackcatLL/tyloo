package io.tyloo.tcctransaction.interceptor;

import com.alibaba.fastjson.JSON;
import io.tyloo.tcctransaction.Transaction;
import io.tyloo.tcctransaction.TransactionManager;
import io.tyloo.tcctransaction.exception.NoExistedTransactionException;
import io.tyloo.tcctransaction.exception.SystemException;
import io.tyloo.tcctransaction.utils.ReflectionUtils;
import io.tyloo.tcctransaction.utils.TransactionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.aspectj.lang.ProceedingJoinPoint;
import io.tyloo.api.Status;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/*
 * 可补偿事务拦截器。
 *
 * @Author:Zh1Cheung 945503088@qq.com
 * @Date: 15:18 2019/12/4
 *
 */
public class TylooInterceptor {

    static final Logger logger = Logger.getLogger(TylooInterceptor.class.getSimpleName());

    /**
     * 事务配置器
     */
    private TransactionManager transactionManager;

    private Set<Class<? extends Exception>> delayCancelExceptions = new HashSet<Class<? extends Exception>>();

    /**
     * 设置事务配置器.
     *
     * @param transactionManager
     */
    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public void setDelayCancelExceptions(Set<Class<? extends Exception>> delayCancelExceptions) {
        this.delayCancelExceptions.addAll(delayCancelExceptions);
    }

    /**
     * 拦截补偿方法.
     *
     * @param pjp
     * @throws Throwable
     */
    public Object interceptTylooMethod(ProceedingJoinPoint pjp) throws Throwable {

        // 从拦截方法的参数中获取事务上下文
        TylooMethodContext tylooMethodContext = new TylooMethodContext(pjp);

        boolean isTransactionActive = transactionManager.isTransactionActive();

        if (!TransactionUtils.isLegalTransactionContext(isTransactionActive, tylooMethodContext)) {
            throw new SystemException("no active tyloo transaction while propagation is mandatory for method " + tylooMethodContext.getMethod().getName());
        }

        // 计算可补偿事务方法类型
        switch (tylooMethodContext.getMethodRole(isTransactionActive)) {
            case ROOT:
                return rootMethodProceed(tylooMethodContext); // 主事务方法的处理
            case PROVIDER:
                return providerMethodProceed(tylooMethodContext); // 服务提供者事务方法处理
            default:
                return pjp.proceed(); // 其他的方法都是直接执行
        }
    }

    /**
     * 主事务方法的处理.
     *
     * @param tylooMethodContext
     * @throws Throwable
     */
    private Object rootMethodProceed(TylooMethodContext tylooMethodContext) throws Throwable {

        Object returnValue = null;

        Transaction transaction = null;

        boolean asyncConfirm = tylooMethodContext.getAnnotation().asyncConfirm();

        boolean asyncCancel = tylooMethodContext.getAnnotation().asyncCancel();

        Set<Class<? extends Exception>> allDelayCancelExceptions = new HashSet<Class<? extends Exception>>();
        allDelayCancelExceptions.addAll(this.delayCancelExceptions);
        allDelayCancelExceptions.addAll(Arrays.asList(tylooMethodContext.getAnnotation().delayCancelExceptions()));

        try {

            // 事务开始（创建事务日志记录，并在当前线程缓存该事务日志记录）
            transaction = transactionManager.begin(tylooMethodContext.getUniqueIdentity());

            try {
                // Try (开始执行被拦截的方法)
                returnValue = tylooMethodContext.proceed();
            } catch (Throwable tryingException) {

                if (!isDelayCancelException(tryingException, allDelayCancelExceptions)) {

                    logger.warn(String.format("tyloo transaction trying failed. transaction content:%s", JSON.toJSONString(transaction)), tryingException);

                    transactionManager.rollback(asyncCancel);
                }

                throw tryingException;
            }

            // Try检验正常后提交(事务管理器在控制提交)
            transactionManager.commit(asyncConfirm);

        } finally {
            transactionManager.cleanAfterCompletion(transaction);
        }

        return returnValue;
    }

    /**
     * 服务提供者事务方法处理.
     * 根据事务的状态是 CONFIRMING / CANCELLING 来调用对应方法
     *
     * @param tylooMethodContext
     * @throws Throwable
     */
    private Object providerMethodProceed(TylooMethodContext tylooMethodContext) throws Throwable {

        Transaction transaction = null;


        boolean asyncConfirm = tylooMethodContext.getAnnotation().asyncConfirm();

        boolean asyncCancel = tylooMethodContext.getAnnotation().asyncCancel();

        try {

            switch (Status.valueOf(tylooMethodContext.getTylooContext().getStatus())) {
                case TRYING:
                    // 基于全局事务ID扩展创建新的分支事务，并存于当前线程的事务局部变量中.
                    transaction = transactionManager.propagationNewBegin(tylooMethodContext.getTylooContext());
                    return tylooMethodContext.proceed();
                case CONFIRMING:
                    try {
                        // 找出存在的事务并处理.
                        transaction = transactionManager.propagationExistBegin(tylooMethodContext.getTylooContext());
                        // 提交
                        transactionManager.commit(asyncConfirm);
                    } catch (NoExistedTransactionException excepton) {
                        //the transaction has been commit,ignore it.
                    }
                    break;
                case CANCELLING:

                    try {
                        transaction = transactionManager.propagationExistBegin(tylooMethodContext.getTylooContext());
                        // 回滚
                        transactionManager.rollback(asyncCancel);
                    } catch (NoExistedTransactionException exception) {
                        //the transaction has been rollback,ignore it.
                    }
                    break;
            }

        } finally {
            transactionManager.cleanAfterCompletion(transaction);
        }

        Method method = tylooMethodContext.getMethod();

        return ReflectionUtils.getNullValue(method.getReturnType());
    }

    private boolean isDelayCancelException(Throwable throwable, Set<Class<? extends Exception>> delayCancelExceptions) {

        if (delayCancelExceptions != null) {
            for (Class delayCancelException : delayCancelExceptions) {

                Throwable rootCause = ExceptionUtils.getRootCause(throwable);

                if (delayCancelException.isAssignableFrom(throwable.getClass())
                        || (rootCause != null && delayCancelException.isAssignableFrom(rootCause.getClass()))) {
                    return true;
                }
            }
        }

        return false;
    }

}
