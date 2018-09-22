package org.eso.ias.asce.transfer;

import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

import org.eso.ias.converter.Converter;
import org.eso.ias.types.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * TransferExecutor is the abstract for the
 * implementators of the transfer function in java.
 * 
 * @author acaproni
 *
 */
public abstract class TransferExecutor {
	
	/**
	 * The ID of the computational element that runs this
	 * transfer function
	 */
	protected final String compElementId;
	
	/**
	 * The ID of the computational element that runs this
	 * transfer function extended with the IDs of its parents
	 * 
	 * @see Identifier
	 */
	protected final String compElementRunningId;
	
	/**
	 * The time frame (msec) to invalidate monitor points that have been refreshed
	 * after the time frame.
	 * 
	 * The validityTimeFrame is given by autoRefreshRate+tolerance 
	 */
	protected final long validityTimeFrame;
	
	/**
	 * Properties for this executor.
	 */
	protected final Properties props;
	
	/**
	 * If defined, this is the instance of the ASCE where the TF runs;
	 * if empty the ASCE is not generated out of a template
	 */
	private Optional<Integer> templateInstance = Optional.empty();

    /**
     * The logger
     */
    private static final Logger logger = LoggerFactory.getLogger(Converter.class);
	
	/**
	 * Constructor
	 * 
	 * @param cEleId: The id of the ASCE
	 * @param cEleRunningId: the running ID of the ASCE
	 * @param validityTimeFrame: The time frame (msec) to invalidate monitor points
	 * @param props: The properties fro the executor
	 */
	public TransferExecutor(
			String cEleId, 
			String cEleRunningId,
			long validityTimeFrame,
			Properties props) {
		Objects.requireNonNull(cEleId,"The ID of the ASCE can't be null!");
		this.compElementId=cEleId;
		Objects.requireNonNull(cEleRunningId,"The running ID can't be null!");
		this.compElementRunningId=cEleRunningId;
		
		if (validityTimeFrame<0) {
			throw new IllegalArgumentException("Time frame must be greater then 0");
		}
		this.validityTimeFrame=validityTimeFrame;
		
		Objects.requireNonNull(props,"The properties can't be null!");
		this.props=props;
		
		if (cEleId.isEmpty() || compElementRunningId.isEmpty()) {
			throw new IllegalArgumentException("Invalid empty ID and/orf FullRunningID");
		}
	}

	/**
	 * Set the instance of the template, if any
	 *
	 * @param templateInstance the instance of the template
	 */
	public final void setTemplateInstance(Optional<Integer> templateInstance) {
		Objects.requireNonNull(templateInstance);
		if (templateInstance.isPresent()) {
		    logger.info("This TF IS templated with instance {}",templateInstance.get());
        } else {
		    logger.info("This TF is not templated");
        }
		this.templateInstance=templateInstance;
	}
	
	/**
	 * @return <code>true</code> if the ASCE where the TF runs has been
	 *         generated by a template; <code>false</code> otherwise
	 */
	public boolean isTemplated() {
		return templateInstance.isPresent();
	}
	
	/**
	 * Shuts down the BehaviorRunner when the IAS does not need it anymore.
	 * 
	 * This life cycle method is called last, to clean up the resources.
	 * 
	 * It is supposed to return quickly, even if not mandatory.
	 * 
	 * @throws Exception In case of error shutting down
	 */
	public abstract void shutdown() throws Exception;

	/**
	 * 
	 * @return The instance of the template, if defined; empty otherwise
	 * @see #templateInstance
	 */
	public Optional<Integer> getTemplateInstance() {
		return templateInstance;
	}
}
