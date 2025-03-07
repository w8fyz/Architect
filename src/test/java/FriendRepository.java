import sh.fyz.architect.repositories.GenericRelayRepository;

public class FriendRepository extends GenericRelayRepository<Friend> {

    public FriendRepository() {
        super(Friend.class);
    }

}
